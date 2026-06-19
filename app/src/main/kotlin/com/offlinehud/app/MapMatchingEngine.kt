package com.offlinehud.app

import android.content.Context
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.MaxSpeed
import com.graphhopper.storage.index.LocationIndex
import com.graphhopper.storage.index.Snap
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.shapes.GHPoint
import java.io.File

class MapMatchingEngine(private val baseDir: File) {
    companion object {
        private const val TAG = "MapMatchingEngine"
    }

    private var hopper: GraphHopper? = null
    private var isInitialized = false
    private var maxSpeedEnc: DecimalEncodedValue? = null

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
                val gh = GraphHopper().apply {
                    osmFile = ""
                    graphHopperLocation = graphFolder.absolutePath
                    profiles = listOf(Profile("car").setVehicle("car").setWeighting("fastest"))
                }
                
                gh.importOrLoad()
                
                hopper = gh
                maxSpeedEnc = gh.encodingManager.getDecimalEncodedValue(MaxSpeed.KEY)
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

                val gh = GraphHopper().apply {
                    this.osmFile = osmFile.absolutePath
                    graphHopperLocation = graphFolder.absolutePath
                    profiles = listOf(Profile("car").setVehicle("car").setWeighting("fastest"))
                }
                
                gh.importOrLoad()
                
                hopper = gh
                maxSpeedEnc = gh.encodingManager.getDecimalEncodedValue(MaxSpeed.KEY)
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

    /**
     * Find nearest road edge and return the max speed limit in km/h.
     * Restricts matching to a maximum of 50 meters distance.
     */
    fun getSpeedLimit(latitude: Double, longitude: Double): Double? {
        if (!isInitialized) return null
        
        val currentHopper = hopper ?: return null
        try {
            val index: LocationIndex = currentHopper.locationIndex
            val snap: Snap = index.findClosest(latitude, longitude, com.graphhopper.routing.util.EdgeFilter.ALL_EDGES)

            if (snap.isValid) {
                val snappedPoint = snap.snappedPoint
                val dist = calculateDistanceMeters(latitude, longitude, snappedPoint.lat, snappedPoint.lon)
                
                // Enforce 50m max distance to avoid off-road mapping mismatch
                if (dist > 50.0) {
                    return null
                }

                val edge: EdgeIteratorState = snap.closestEdge
                val speedEnc = maxSpeedEnc
                if (speedEnc != null) {
                    val speed = edge.get(speedEnc)
                    if (!speed.isNaN() && speed > 0 && speed < 200) {
                        return speed
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching speed limit at $latitude, $longitude", e)
            logErrorToFile("Error matching speed limit at $latitude, $longitude", e)
        }
        return null
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
