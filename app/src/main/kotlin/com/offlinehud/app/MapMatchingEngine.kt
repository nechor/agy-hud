package com.offlinehud.app

import android.content.Context
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.shapes.GHPoint
import java.io.File

data class SpeedLimitResult(
    val limit: Double,
    val roadName: String,
    val roadClass: String,
    val status: Int,
    val isUrban: Boolean
)

class MapMatchingEngine(private val baseDir: File) {
    companion object {
        private const val TAG = "MapMatchingEngine"
        init {
            System.setProperty("javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl")
            System.setProperty("javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl")
            System.setProperty("javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl")
        }
    }

    private var hopper: GraphHopper? = null
    private var isInitialized = false
    private var maxSpeedEnc: DecimalEncodedValue? = null
    private var roadClassEnc: EnumEncodedValue<RoadClass>? = null
    private var carAccessEnc: com.graphhopper.routing.ev.BooleanEncodedValue? = null
    private var onewayEnc: com.graphhopper.routing.ev.BooleanEncodedValue? = null

    /**
     * Initializes the GraphHopper instance if pre-compiled files exist.
     */
    fun initialize(onComplete: (Boolean) -> Unit) {
        val graphFolder = File(baseDir, "graphhopper")
        if (!graphFolder.exists() || graphFolder.list()?.isEmpty() == true) {
            Log.w(TAG, "GraphHopper directory is empty or does not exist: ${graphFolder.absolutePath}.")
            isInitialized = false
            onComplete(false)
            return
        }

        Thread {
            try {
                val gh = AndroidGraphHopper().apply {
                    val config = com.graphhopper.GraphHopperConfig().apply {
                        putObject("graph.dataaccess.default_type", "MMAP")
                        putObject("graph.location", graphFolder.absolutePath)
                        putObject("import.osm.ignored_highways", "")
                    }
                    init(config)
                    profiles = listOf(Profile("car").setVehicle("car").setWeighting("custom"))
                }
                
                if (!gh.load()) {
                    throw IllegalStateException("Failed to load GraphHopper directory")
                }
                
                hopper = gh
                maxSpeedEnc = gh.encodingManager.getDecimalEncodedValue(MaxSpeed.KEY)
                if (gh.encodingManager.hasEncodedValue(RoadClass.KEY)) {
                    roadClassEnc = gh.encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
                }
                if (gh.encodingManager.hasEncodedValue(VehicleAccess.key("car"))) {
                    carAccessEnc = gh.encodingManager.getBooleanEncodedValue(VehicleAccess.key("car"))
                }
                if (gh.encodingManager.hasEncodedValue("oneway")) {
                    onewayEnc = gh.encodingManager.getBooleanEncodedValue("oneway")
                }
                isInitialized = true
                Log.i(TAG, "GraphHopper loaded successfully from storage!")
                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load GraphHopper", e)
                logErrorToFile("Failed to load GraphHopper", e)
                isInitialized = false
                onComplete(false)
            }
        }.start()
    }

    /**
     * Deletes existing graph directory and compiles a raw OSM XML file on-device.
     */
    fun importOsmFile(osmFile: File, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                Log.i(TAG, "Starting import of OSM file: ${osmFile.absolutePath}")
                close()

                val graphFolder = File(baseDir, "graphhopper")
                if (graphFolder.exists()) {
                    graphFolder.deleteRecursively()
                }
                graphFolder.mkdirs()

                val gh = AndroidGraphHopper().apply {
                    this.osmFile = osmFile.absolutePath
                    graphHopperLocation = graphFolder.absolutePath
                    profiles = listOf(Profile("car").setVehicle("car").setWeighting("custom"))
                }
                
                gh.importOrLoad()
                
                hopper = gh
                maxSpeedEnc = gh.encodingManager.getDecimalEncodedValue(MaxSpeed.KEY)
                if (gh.encodingManager.hasEncodedValue(RoadClass.KEY)) {
                    roadClassEnc = gh.encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass::class.java)
                }
                if (gh.encodingManager.hasEncodedValue(VehicleAccess.key("car"))) {
                    carAccessEnc = gh.encodingManager.getBooleanEncodedValue(VehicleAccess.key("car"))
                }
                if (gh.encodingManager.hasEncodedValue("oneway")) {
                    onewayEnc = gh.encodingManager.getBooleanEncodedValue("oneway")
                }
                isInitialized = true
                Log.i(TAG, "GraphHopper compiled OSM XML data successfully!")
                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compile OSM XML data", e)
                logErrorToFile("Failed to compile OSM XML data", e)
                onComplete(false)
            }
        }.start()
    }

    fun getSpeedLimit(latitude: Double, longitude: Double): SpeedLimitResult? {
        if (!isInitialized) return null
        
        val currentHopper = hopper ?: return null
        try {
            val index: LocationIndex = currentHopper.locationIndex
            val accessEnc = carAccessEnc
            val filter = if (accessEnc != null) {
                com.graphhopper.routing.util.EdgeFilter { edge -> edge.get(accessEnc) }
            } else {
                com.graphhopper.routing.util.EdgeFilter.ALL_EDGES
            }
            val snap: Snap = index.findClosest(latitude, longitude, filter)

            if (snap.isValid) {
                val snappedPoint = snap.snappedPoint
                val dist = calculateDistanceMeters(latitude, longitude, snappedPoint.lat, snappedPoint.lon)
                
                // Enforce 50m max distance to avoid off-road mapping mismatch
                if (dist > 50.0) {
                    return null
                }

                val edge: EdgeIteratorState = snap.closestEdge
                val roadName = edge.name ?: ""
                val rEnc = roadClassEnc
                val roadClassStr = if (rEnc != null) {
                    edge.get(rEnc)?.name ?: "UNKNOWN"
                } else {
                    "UNKNOWN"
                }

                val speedEnc = maxSpeedEnc
                if (speedEnc != null) {
                    val speed = edge.get(speedEnc)
                    if (!speed.isNaN() && speed > 0 && speed < 200) {
                        val isUrbanResult = isLikelyUrbanRoad(roadName) || 
                                           (rEnc != null && (edge.get(rEnc) == RoadClass.RESIDENTIAL || edge.get(rEnc) == RoadClass.LIVING_STREET || edge.get(rEnc) == RoadClass.UNCLASSIFIED))
                        return SpeedLimitResult(speed, roadName, roadClassStr, 2, isUrbanResult)
                    }
                }
                
                // Fallback to road class default limit
                if (rEnc != null) {
                    val rClass = edge.get(rEnc)
                    if (rClass != null) {
                        val isUrbanResult = isLikelyUrbanRoad(roadName) || 
                                           rClass == RoadClass.RESIDENTIAL || 
                                           rClass == RoadClass.LIVING_STREET ||
                                           rClass == RoadClass.UNCLASSIFIED
                        
                        val isOneway = if (accessEnc != null) {
                            val fwd = edge.get(accessEnc)
                            val bwd = edge.getReverse(accessEnc)
                            fwd != bwd
                        } else {
                            onewayEnc?.let { edge.get(it) } ?: false
                        }
                        
                        val (fallback, status) = when (rClass) {
                            RoadClass.MOTORWAY -> 140.0 to 6
                            RoadClass.TRUNK -> {
                                val isExpressway = roadName.matches(Regex("^[sS]\\s*-?\\d+.*"))
                                if (isExpressway) {
                                    if (isOneway) 120.0 to 6 else 100.0 to 6
                                } else {
                                    if (isUrbanResult) {
                                        50.0 to 6
                                    } else {
                                        if (isOneway) 100.0 to 6 else 90.0 to 6
                                    }
                                }
                            }
                            RoadClass.PRIMARY, RoadClass.SECONDARY, RoadClass.TERTIARY -> {
                                if (isUrbanResult) {
                                    50.0 to 6
                                } else {
                                    if (isOneway) 100.0 to 6 else 90.0 to 6
                                }
                            }
                            RoadClass.RESIDENTIAL -> 50.0 to 6
                            RoadClass.LIVING_STREET -> 20.0 to 6
                            RoadClass.SERVICE -> 30.0 to 6
                            RoadClass.TRACK -> 30.0 to 6
                            RoadClass.UNCLASSIFIED -> 50.0 to 6
                            else -> 50.0 to 4 // ROAD, OTHER, etc. -> default 50.0, status 4 (Purple)
                        }
                        return SpeedLimitResult(fallback, roadName, roadClassStr, status, isUrbanResult)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching speed limit at $latitude, $longitude", e)
            logErrorToFile("Error matching speed limit at $latitude, $longitude", e)
        }
        return null
    }

    private fun isLikelyUrbanRoad(roadName: String): Boolean {
        if (roadName.isEmpty()) return false
        val ruralPatterns = listOf(
            "dk", "dw", "droga krajowa", "droga wojewódzka", "droga powiatowa", "autostrada", "droga ekspresowa"
        )
        val nameLower = roadName.lowercase().trim()
        for (pattern in ruralPatterns) {
            if (nameLower.contains(pattern)) return false
        }
        if (nameLower.matches(Regex("\\d+[a-z]?"))) return false
        if (nameLower.matches(Regex("^[as]\\s*-?\\d+.*"))) return false
        return true
    }

    fun getQueryFailureReason(latitude: Double, longitude: Double): String {
        if (!isInitialized || hopper == null) return "No Map Loaded"
        try {
            val index: LocationIndex = hopper!!.locationIndex
            val accessEnc = carAccessEnc
            val filter = if (accessEnc != null) {
                com.graphhopper.routing.util.EdgeFilter { edge -> edge.get(accessEnc) }
            } else {
                com.graphhopper.routing.util.EdgeFilter.ALL_EDGES
            }
            val snap: Snap = index.findClosest(latitude, longitude, filter)
            if (!snap.isValid) {
                return "No Roads Found"
            }
            val snappedPoint = snap.snappedPoint
            val dist = calculateDistanceMeters(latitude, longitude, snappedPoint.lat, snappedPoint.lon)
            if (dist > 50.0) {
                return "Off-road (${dist.toInt()}m)"
            }
            val edge: EdgeIteratorState = snap.closestEdge
            val speedEnc = maxSpeedEnc
            if (speedEnc != null) {
                val speed = edge.get(speedEnc)
                if (!speed.isNaN() && speed > 0 && speed < 200) {
                    return "OK"
                }
            }
            val rEnc = roadClassEnc
            if (rEnc != null) {
                val rClass = edge.get(rEnc)
                if (rClass != null) {
                    return "OK"
                }
            }
            return "No Limit Tag"
        } catch (e: Exception) {
            return "Query Error"
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        try {
            android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0].toDouble()
        } catch (e: Exception) {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return 6371000 * c
        }
    }

    fun close() {
        try {
            hopper?.close()
            hopper = null
            isInitialized = false
            Log.i(TAG, "GraphHopper instance closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GraphHopper", e)
            logErrorToFile("Error closing GraphHopper", e)
        }
    }

    private fun logErrorToFile(message: String, throwable: Throwable? = null) {
        try {
            val logFile = File(baseDir, "error_logs.txt")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val stackTrace = throwable?.let { android.util.Log.getStackTraceString(it) } ?: ""
            val entry = "[$timestamp] [MapMatchingEngine] $message\n$stackTrace\n"
            logFile.appendText(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    fun isReady(): Boolean = isInitialized
    fun isMockMode(): Boolean = !isInitialized || hopper == null
}

class DirectWeighting(
    private val accessEnc: com.graphhopper.routing.ev.BooleanEncodedValue,
    private val speedEnc: DecimalEncodedValue
) : Weighting {
    override fun getMinWeight(distance: Double): Double {
        return distance / 150.0
    }

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val speed = edgeState.get(speedEnc)
        if (speed <= 0.0) return Double.POSITIVE_INFINITY
        return edgeState.distance / (speed / 3.6)
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long {
        val speed = edgeState.get(speedEnc)
        if (speed <= 0.0) return Long.MAX_VALUE
        return (edgeState.distance / (speed / 3.6) * 1000.0).toLong()
    }

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double {
        return 0.0
    }

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long {
        return 0L
    }

    override fun getName(): String {
        return "custom"
    }

    override fun hasTurnCosts(): Boolean {
        return false
    }
}

class AndroidGraphHopper : GraphHopper() {
    override fun createWeightingFactory(): WeightingFactory {
        return WeightingFactory { profile, _, _ ->
            val vehicle = profile.vehicle
            val accessEnc = encodingManager.getBooleanEncodedValue(VehicleAccess.key(vehicle))
            val speedEnc = encodingManager.getDecimalEncodedValue(VehicleSpeed.key(vehicle))
            DirectWeighting(accessEnc, speedEnc)
        }
    }
}
