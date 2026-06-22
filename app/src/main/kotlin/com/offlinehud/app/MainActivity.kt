package com.offlinehud.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.io.IOException
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.floor
import kotlin.math.abs

data class FetchResult(val id: Long, val status: Int)

class MainActivity : ComponentActivity(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var srtmProvider: SrtmElevationProvider
    private lateinit var mapMatchingEngine: MapMatchingEngine

    // UI States
    private val speed = mutableStateOf(0.0) // km/h
    private val elevation = mutableStateOf<Short?>(null) // meters
    private val speedLimit = mutableStateOf<Int?>(null) // km/h
    private val bearing = mutableStateOf(0.0f) // degrees
    private val isSymmetricMirror = mutableStateOf(false)
    private val gpsStatus = mutableStateOf("No GPS Fix")
    private val mapStatus = mutableStateOf("Offline Map: Not Loaded")
    private val limitStatus = mutableStateOf("No Map")
    private val roadClass = mutableStateOf("")
    private val isUrbanArea = mutableStateOf(false)
    private val currentFetchStatus = mutableStateOf(0)
    private val downloadProgress = mutableStateOf<Float?>(null)
    private val syncPhase = mutableStateOf<String?>(null)
    private val showDownloadSuccessDialog = mutableStateOf(false)
    private val downloadErrorText = mutableStateOf<String?>(null)

    // History logs for step charts
    val speedHistory = mutableStateListOf<Float>()
    val speedColorHistory = mutableStateListOf<Color>()
    val elevationHistory = mutableStateListOf<Float>()
    private val locationHistory = mutableListOf<Pair<Double, Double>>()

    // Hysteresis helper
    private var lastValidSpeedLimit: Int? = null
    private var lastSpeedLimitTime: Long = 0L
    private val limitExpiryMs = 5000L // 5 seconds hysteresis

    private var lastLocationTime = System.currentTimeMillis()
    private val gpsTimeoutHandler = Handler(Looper.getMainLooper())
    private val gpsTimeoutRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastLocationTime > 2000L) {
                speed.value = 0.0
                speedHistory.add(0f)
                if (speedHistory.size > 25) speedHistory.removeAt(0)
                speedColorHistory.add(Color(0xFF00F0FF))
                if (speedColorHistory.size > 25) speedColorHistory.removeAt(0)
            }
            gpsTimeoutHandler.postDelayed(this, 1000L)
        }
    }

    // Speed limit query status history (last 10 attempts)
    val fetchHistory = mutableStateListOf<FetchResult>()
    private var fetchIdSequence = 0L
    val okFetchCount = mutableStateOf(0)
    val deducedFetchCount = mutableStateOf(0)
    val offRoadFetchCount = mutableStateOf(0)
    val noLimitFetchCount = mutableStateOf(0)
    val noRoadsFetchCount = mutableStateOf(0)
    val noMapFetchCount = mutableStateOf(0)

    // Compiled map metadata
    private var cacheCenterLat = 0.0
    private var cacheCenterLon = 0.0
    private var cacheTimestamp = 0L
    private var isDownloadingCache = false

    private fun recordFetchResult(status: Int) { // 0 = fail, 1 = api_success, 2 = cache_success
        runOnUiThread {
            fetchHistory.add(FetchResult(fetchIdSequence++, status))
            if (fetchHistory.size > 20) {
                fetchHistory.removeAt(0)
            }
            when (status) {
                1, 2 -> okFetchCount.value += 1
                6 -> deducedFetchCount.value += 1
                3 -> offRoadFetchCount.value += 1
                4 -> noLimitFetchCount.value += 1
                5 -> noRoadsFetchCount.value += 1
                else -> noMapFetchCount.value += 1
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLocationUpdates()
        } else {
            gpsStatus.value = "Permissions Denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on during HUD navigation to prevent device sleeping
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        srtmProvider = SrtmElevationProvider(filesDir)
        mapMatchingEngine = MapMatchingEngine(filesDir)

        gpsTimeoutHandler.post(gpsTimeoutRunnable)
        loadCacheFromFile()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Setup demo files if they don't exist, to support offline test flows out of the box
        createSampleSrtmFile(52.2297, 21.0122)

        mapMatchingEngine.initialize { success ->
            runOnUiThread {
                mapStatus.value = if (success) "Offline Map: Ready" else "Offline Map: Demo Mode (No Map Files)"
            }
        }

        checkAndRequestPermissions()



        setContent {
            MaterialTheme {
                HudScreen(
                    speed = speed.value,
                    elevation = elevation.value,
                    speedLimit = speedLimit.value,
                    bearing = bearing.value,
                    isMirror = isSymmetricMirror.value,
                    gpsStatus = gpsStatus.value,
                    mapStatus = mapStatus.value,
                    limitStatus = limitStatus.value,
                    roadClass = roadClass.value,
                    isUrbanArea = isUrbanArea.value,
                    currentFetchStatus = currentFetchStatus.value,
                    speedHistory = speedHistory,
                    speedColorHistory = speedColorHistory,
                    elevationHistory = elevationHistory,
                    downloadProgress = downloadProgress.value,
                    syncPhase = syncPhase.value,
                    fetchHistory = fetchHistory,
                    okFetchCount = okFetchCount.value,
                    deducedFetchCount = deducedFetchCount.value,
                    offRoadFetchCount = offRoadFetchCount.value,
                    noLimitFetchCount = noLimitFetchCount.value,
                    noRoadsFetchCount = noRoadsFetchCount.value,
                    noMapFetchCount = noMapFetchCount.value,
                    onToggleMirror = { isSymmetricMirror.value = !isSymmetricMirror.value },
                    onDownloadData = { downloadOfflineData() }
                )

                if (showDownloadSuccessDialog.value) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showDownloadSuccessDialog.value = false },
                        title = {
                            Text(
                                text = "DOWNLOAD COMPLETE",
                                color = Color(0xFF00FF88),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        },
                        text = {
                            Text(
                                text = "Map database of Poland successfully downloaded and initialized. Offline map matching is ready.",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showDownloadSuccessDialog.value = false }
                            ) {
                                Text(
                                    "OK",
                                    color = Color(0xFF00FF88),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        },
                        containerColor = Color(0xFF0F172A),
                        textContentColor = Color.White
                    )
                }

                if (downloadErrorText.value != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { downloadErrorText.value = null },
                        title = {
                            Text(
                                text = "DOWNLOAD ERROR",
                                color = Color(0xFFFF5252),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        },
                        text = {
                            Text(
                                text = downloadErrorText.value ?: "Unknown error occurred.",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { downloadErrorText.value = null }
                            ) {
                                Text(
                                    "OK",
                                    color = Color(0xFFFF5252),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        },
                        containerColor = Color(0xFF0F172A),
                        textContentColor = Color.White
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocationGranted) {
            startLocationUpdates()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1 second interval
                1f, // 1 meter change
                this
            )
            gpsStatus.value = "Searching for GPS..."
        } catch (e: SecurityException) {
            gpsStatus.value = "Security Exception"
        }
    }

    override fun onLocationChanged(location: Location) {
        processNewLocation(location.latitude, location.longitude, (location.speed * 3.6))
    }

    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        try {
            Location.distanceBetween(lat1, lon1, lat2, lon2, results)
            return results[0].toDouble()
        } catch (e: Exception) {
            // Approximation if static method fails
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return 6371000 * c
        }
    }

    private fun processNewLocation(lat: Double, lon: Double, currentSpeedKmh: Double) {
        lastLocationTime = System.currentTimeMillis()
        speed.value = currentSpeedKmh
        gpsStatus.value = String.format("GPS Fix: %.4f, %.4f", lat, lon)

        // Dynamically verify and generate local SRTM elevation grid file for the current GPS region
        createSampleSrtmFile(lat, lon)

        // Read Elevation from local SRTM files
        val elev = srtmProvider.getElevation(lat, lon)
        elevation.value = elev

        // Log history for charts
        speedHistory.add(currentSpeedKmh.toFloat())
        if (speedHistory.size > 25) speedHistory.removeAt(0)

        val elevVal = elev ?: 0.toShort()
        elevationHistory.add(elevVal.toFloat())
        if (elevationHistory.size > 25) elevationHistory.removeAt(0)

        // Calculate bearing based on traveled points history (minimum 3 km/h to filter GPS noise)
        if (currentSpeedKmh > 3.0) {
            locationHistory.add(Pair(lat, lon))
            if (locationHistory.size > 8) {
                locationHistory.removeAt(0)
            }
            // Use coordinate from 3 steps ago (or oldest in history) to get a stable direction vector
            if (locationHistory.size >= 4) {
                val oldPoint = locationHistory[locationHistory.size - 4]
                bearing.value = calculateBearing(oldPoint.first, oldPoint.second, lat, lon).toFloat()
            } else if (locationHistory.size >= 2) {
                val oldPoint = locationHistory[0]
                bearing.value = calculateBearing(oldPoint.first, oldPoint.second, lat, lon).toFloat()
            }
        }

        // Cache/optimize Map Matching queries if the driver hasn't moved much (less than 5 meters)
        val distanceMoved = calculateDistanceMeters(lat, lon, lastQueryLat, lastQueryLon)
        val limit = if (distanceMoved >= 5.0 || speedLimit.value == null) {
            lastQueryLat = lat
            lastQueryLon = lon

            val match = mapMatchingEngine.getSpeedLimit(lat, lon)
            val localLimit = match?.limit?.roundToInt()
            if (match != null && localLimit != null) {
                limitStatus.value = if (match.roadName.isNotEmpty()) {
                    match.roadName
                } else {
                    match.roadClass
                }
                roadClass.value = match.roadClass
                isUrbanArea.value = match.isUrban
                currentFetchStatus.value = match.status
                recordFetchResult(match.status)
                localLimit
            } else {
                val reason = mapMatchingEngine.getQueryFailureReason(lat, lon)
                limitStatus.value = reason
                roadClass.value = ""
                isUrbanArea.value = false
                
                val statusInt = when {
                    reason.contains("No Map", ignoreCase = true) -> 0 // Gray
                    reason.contains("No Roads", ignoreCase = true) -> 5 // Blue
                    reason.contains("Off-road", ignoreCase = true) -> 3 // Light Red
                    reason.contains("No Limit", ignoreCase = true) -> 4 // Purple
                    else -> 0
                }
                currentFetchStatus.value = statusInt
                recordFetchResult(statusInt)
                
                // Auto-trigger background download if we have no map and it's not pre-compiled
                if (cacheCenterLat != -1.0) {
                    val distFromCenter = calculateDistanceMeters(lat, lon, cacheCenterLat, cacheCenterLon)
                    if (cacheCenterLat == 0.0 || distFromCenter > 3500.0) {
                        downloadOfflineDataAtLocation(lat, lon)
                    }
                }
                null
            }
        } else {
            speedLimit.value
        }

        val now = System.currentTimeMillis()
        if (limit != null) {
            speedLimit.value = limit
            lastValidSpeedLimit = limit
            lastSpeedLimitTime = now
        } else {
            // Apply Hysteresis: freeze speed limit for 5s before setting to null
            if (lastValidSpeedLimit != null && (now - lastSpeedLimitTime) < limitExpiryMs) {
                speedLimit.value = lastValidSpeedLimit
            } else {
                speedLimit.value = null
            }
        }

        // Calculate and log speed color history for the timeline chart
        val limitVal = speedLimit.value
        val pointColor = if (limitVal != null) {
            val ratio = currentSpeedKmh / limitVal
            when {
                ratio <= 0.8 -> Color(0xFF00F0FF)    // Blue
                ratio <= 1.0 -> Color(0xFF00FF88)    // Green
                ratio <= 1.1 -> Color(0xFFFFB74D)    // Yellow
                else -> Color(0xFFFF0055)            // Red
            }
        } else {
            Color(0xFF00F0FF)
        }
        speedColorHistory.add(pointColor)
        if (speedColorHistory.size > 25) speedColorHistory.removeAt(0)
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val lonDiffRad = Math.toRadians(lon2 - lon1)

        val y = Math.sin(lonDiffRad) * Math.cos(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(lonDiffRad)

        val bearingRad = Math.atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }



    /**
     * Creates a dummy SRTM file for the specified GPS grid coordinates if not already present.
     */
    private fun createSampleSrtmFile(latitude: Double, longitude: Double) {
        val srtmDir = File(filesDir, "srtm")
        if (!srtmDir.exists()) srtmDir.mkdirs()

        val latFloor = floor(latitude).toInt()
        val lonFloor = floor(longitude).toInt()

        val latPrefix = if (latFloor >= 0) "N" else "S"
        val latString = String.format("%s%02d", latPrefix, Math.abs(latFloor))

        val lonPrefix = if (lonFloor >= 0) "E" else "W"
        val lonString = String.format("%s%03d", lonPrefix, Math.abs(lonFloor))

        val fileName = "$latString$lonString.hgt"
        val file = File(srtmDir, fileName)
        if (!file.exists()) {
            try {
                // SRTM-3 size: 1201 x 1201 * 2 bytes = 2884802 bytes
                val buffer = ByteArray(2884802)
                // Fill with slightly varying elevations based on grid position for interactive charts
                for (row in 0 until 1201) {
                    val rowElev = 120 + (row / 15) % 30
                    for (col in 0 until 1201) {
                        val finalElev = (rowElev + (col / 15) % 20).toShort()
                        val idx = ((row * 1201) + col) * 2
                        buffer[idx] = (finalElev.toInt() shr 8).toByte()
                        buffer[idx + 1] = (finalElev.toInt() and 0xFF).toByte()
                    }
                }
                file.writeBytes(buffer)
                Log.d("MainActivity", "Dynamically created local SRTM file: $fileName")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to write dynamic SRTM file: $fileName", e)
            }
        }
    }

    private fun downloadOfflineData() {
        if (downloadProgress.value != null) return
        downloadProgress.value = 0f
        
        var lat = 53.7784
        var lon = 20.4801
        
        synchronized(locationHistory) {
            if (locationHistory.isNotEmpty()) {
                lat = locationHistory.last().first
                lon = locationHistory.last().second
            }
        }
        
        downloadOfflineDataAtLocation(lat, lon)
    }

    private val overpassMirrors = listOf(
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    private fun downloadOfflineDataAtLocation(lat: Double, lon: Double) {
        if (isDownloadingCache) return
        isDownloadingCache = true
        runOnUiThread {
            downloadProgress.value = 0f
            syncPhase.value = "CONNECTING"
        }
        
        Thread {
            try {
                runOnUiThread {
                    mapStatus.value = "Downloading Map..."
                }
                
                val url = java.net.URL("https://media.githubusercontent.com/media/nechor/agy-hud/master/graphhopper.zip")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "OfflineHUDApp/1.0 (contact@example.com)")
                conn.connectTimeout = 60000
                conn.readTimeout = 300000
                
                var status = conn.responseCode
                var downloadUrl = url
                var finalConn = conn
                if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP || 
                    status == java.net.HttpURLConnection.HTTP_MOVED_PERM || 
                    status == 307 || status == 308) {
                    val newUrl = conn.getHeaderField("Location")
                    downloadUrl = java.net.URL(newUrl)
                    finalConn = downloadUrl.openConnection() as java.net.HttpURLConnection
                    finalConn.requestMethod = "GET"
                    finalConn.setRequestProperty("User-Agent", "OfflineHUDApp/1.0 (contact@example.com)")
                    finalConn.connectTimeout = 60000
                    finalConn.readTimeout = 300000
                    status = finalConn.responseCode
                }

                if (status == 200) {
                    val contentLength = finalConn.contentLength
                    val zipFile = File(filesDir, "graphhopper.zip")
                    
                    runOnUiThread {
                        syncPhase.value = "DOWNLOADING"
                        downloadProgress.value = 0f
                        mapStatus.value = "Saving ZIP..."
                    }
                    
                    finalConn.inputStream.use { input ->
                        zipFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                if (contentLength > 0) {
                                    val progress = totalBytesRead.toFloat() / contentLength
                                    runOnUiThread {
                                        downloadProgress.value = progress
                                    }
                                }
                            }
                        }
                    }
                    
                    runOnUiThread {
                        syncPhase.value = "UNZIPPING"
                        downloadProgress.value = 0f
                        mapStatus.value = "Unzipping Map..."
                    }
                    
                    val graphFolder = File(filesDir, "graphhopper")
                    if (graphFolder.exists()) {
                        graphFolder.deleteRecursively()
                    }
                    graphFolder.mkdirs()
                    
                    unzip(zipFile, filesDir) { progress ->
                        runOnUiThread {
                            downloadProgress.value = progress
                        }
                    }
                    
                    if (zipFile.exists()) {
                        zipFile.delete()
                    }
                    
                    runOnUiThread {
                        syncPhase.value = "INITIALIZING"
                        downloadProgress.value = null
                        mapStatus.value = "Loading Map..."
                    }
                    
                    mapMatchingEngine.initialize { success ->
                        runOnUiThread {
                            downloadProgress.value = null
                            syncPhase.value = null
                            isDownloadingCache = false
                            if (success) {
                                cacheCenterLat = -1.0
                                cacheCenterLon = -1.0
                                cacheTimestamp = System.currentTimeMillis()
                                saveCacheToFile()
                                mapStatus.value = "Offline Map: Ready"
                                recordFetchResult(1)
                                showDownloadSuccessDialog.value = true
                            } else {
                                mapStatus.value = "Load Failed"
                                recordFetchResult(0)
                                downloadErrorText.value = "Failed to initialize GraphHopper engine after unzipping."
                            }
                        }
                    }
                } else {
                    Log.e("MainActivity", "GitHub download returned error code: $status")
                    logErrorToFile("MainActivity", "GitHub download returned error code: $status")
                    runOnUiThread {
                        downloadProgress.value = null
                        syncPhase.value = null
                        isDownloadingCache = false
                        mapStatus.value = "Download Failed (HTTP $status)"
                        recordFetchResult(0)
                        downloadErrorText.value = "Failed to connect to server. HTTP Status: $status"
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to download precompiled map data", e)
                logErrorToFile("MainActivity", "Failed to download precompiled map data", e)
                runOnUiThread {
                    downloadProgress.value = null
                    syncPhase.value = null
                    isDownloadingCache = false
                    mapStatus.value = "Download Error"
                    recordFetchResult(0)
                    downloadErrorText.value = "An error occurred during map synchronization:\n${e.localizedMessage ?: e.toString()}"
                }
            }
        }.start()
    }

    private fun unzip(zipFile: File, targetDirectory: File, onProgress: (Float) -> Unit) {
        val zip = java.util.zip.ZipFile(zipFile)
        val totalEntries = zip.size()
        zip.close()
        
        var extractedCount = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(targetDirectory, entry!!.name)
                val dir = if (entry!!.isDirectory) file else file.parentFile
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IOException("Failed to ensure directory: ${dir.absolutePath}")
                }
                if (entry!!.isDirectory) continue
                BufferedOutputStream(FileOutputStream(file)).use { dest ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (zis.read(buffer).also { count = it } != -1) {
                        dest.write(buffer, 0, count)
                    }
                }
                extractedCount++
                if (totalEntries > 0) {
                    onProgress(extractedCount.toFloat() / totalEntries)
                }
            }
        }
    }

    private fun saveCacheToFile() {
        try {
            val file = File(filesDir, "map_metadata.json")
            val root = org.json.JSONObject().apply {
                put("centerLat", cacheCenterLat)
                put("centerLon", cacheCenterLon)
                put("timestamp", cacheTimestamp)
                if (cacheCenterLat == -1.0) {
                    put("precompiled", true)
                }
            }
            file.writeText(root.toString())
            Log.d("MainActivity", "Saved map metadata: $root")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to save map metadata", e)
            logErrorToFile("MainActivity", "Failed to save map metadata", e)
        }
    }

    private fun loadCacheFromFile() {
        try {
            val file = File(filesDir, "map_metadata.json")
            if (file.exists()) {
                val jsonStr = file.readText()
                val root = org.json.JSONObject(jsonStr)
                val timestamp = root.optLong("timestamp", 0L)
                val now = System.currentTimeMillis()
                val oneDayMs = 24 * 60 * 60 * 1000L
                val precompiled = root.optBoolean("precompiled", false) || root.optDouble("centerLat", 0.0) == -1.0
                
                if (precompiled || (now - timestamp < oneDayMs)) {
                    cacheCenterLat = root.optDouble("centerLat", 0.0)
                    cacheCenterLon = root.optDouble("centerLon", 0.0)
                    cacheTimestamp = timestamp
                    Log.d("MainActivity", "Loaded valid map metadata (center: $cacheCenterLat, $cacheCenterLon)")
                } else {
                    Log.d("MainActivity", "Map cache expired (> 24 hours). Cleaning up.")
                    val mapFile = File(filesDir, "map.osm")
                    if (mapFile.exists()) mapFile.delete()
                    val graphFolder = File(filesDir, "graphhopper")
                    if (graphFolder.exists()) graphFolder.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load cache metadata", e)
            logErrorToFile("MainActivity", "Failed to load cache metadata", e)
        }
    }


    private fun logErrorToFile(tag: String, message: String, throwable: Throwable? = null) {
        Thread {
            try {
                val logFile = File(filesDir, "error_logs.txt")
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val stackTrace = throwable?.let { android.util.Log.getStackTraceString(it) } ?: ""
                val entry = "[$timestamp] [$tag] $message\n$stackTrace\n"
                logFile.appendText(entry)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to write log to file", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutRunnable)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}

@Composable
fun HudScreen(
    speed: Double,
    elevation: Short?,
    speedLimit: Int?,
    bearing: Float,
    isMirror: Boolean,
    gpsStatus: String,
    mapStatus: String,
    limitStatus: String,
    roadClass: String,
    isUrbanArea: Boolean,
    currentFetchStatus: Int,
    speedHistory: List<Float>,
    speedColorHistory: List<Color>,
    elevationHistory: List<Float>,
    downloadProgress: Float?,
    syncPhase: String?,
    fetchHistory: List<FetchResult>,
    okFetchCount: Int,
    deducedFetchCount: Int,
    offRoadFetchCount: Int,
    noLimitFetchCount: Int,
    noRoadsFetchCount: Int,
    noMapFetchCount: Int,
    onToggleMirror: () -> Unit,
    onDownloadData: () -> Unit
) {
    val context = LocalContext.current
    val DigitalFontFamily = remember {
        FontFamily(
            Font(path = "dseg7classic.ttf", assetManager = context.assets)
        )
    }

    val overspeed = speedLimit != null && speed > (speedLimit * 1.1)
    
    val targetSpeedColor = if (speedLimit != null) {
        val ratio = speed / speedLimit
        when {
            ratio <= 0.8 -> Color(0xFF00F0FF)    // Blue (0 - 80%)
            ratio <= 1.0 -> Color(0xFF00FF88)    // Green (80% - 100%)
            ratio <= 1.1 -> Color(0xFFFFB74D)    // Yellow (100% - 110%)
            else -> Color(0xFFFF0055)            // Red (> 110%)
        }
    } else {
        Color(0xFF00F0FF) // Default Blue when no limit is logged
    }
    
    val speedColor by animateColorAsState(
        targetValue = targetSpeedColor,
        animationSpec = tween(durationMillis = 300),
        label = "speedColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulsingGlow")
    val pulsingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsingAlpha"
    )
    val showStatusLegend = remember { mutableStateOf(false) }

    if (showStatusLegend.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStatusLegend.value = false },
            title = {
                Text(
                    text = "STATUS LEGEND",
                    color = Color(0xFF00F0FF),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LegendItem(Color(0xFF00FF88), "GREEN (OK)", "OK - Limit read from maxspeed tag on map")
                    LegendItem(Color(0xFFFFB74D), "YELLOW (DED)", "OK - Limit deduced from road class (e.g. 90 km/h rural, 50 km/h urban)")
                    LegendItem(Color(0xFFFF5252), "LIGHT RED (OFF)", "Off-road (distance to nearest road > 50 meters)")
                    LegendItem(Color(0xFFE040FB), "PURPLE (TAG)", "No Speed Limit Tag & unrecognized road (defaulted to 50 km/h)")
                    LegendItem(Color(0xFF448AFF), "BLUE (RD)", "No Roads Found (empty region or pedestrian-only zone)")
                    LegendItem(Color(0xFF90A4AE), "GRAY (MAP)", "No Map Loaded (download offline map or compile OSM)")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showStatusLegend.value = false }
                ) {
                    Text(
                        "DISMISS",
                        color = Color(0xFF00F0FF),
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            containerColor = Color(0xFF0F172A),
            textContentColor = Color.White
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF020617)),
                    radius = 1200f
                )
            )
            .border(
                width = 3.dp,
                color = if (overspeed) Color(0xFFFF0055).copy(alpha = pulsingAlpha) else Color(0xFF1E293B),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp)) // Safe space at the top to prevent overlapping Android statusbar

            // Mirror-eligible HUD Panel Group
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (isMirror) {
                            scaleX = -1f
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

            // 1. Central Speed Gauge Panel (Full Width) with embedded status corner indicators
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(2.3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1A0F172A))
                    .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                // Embed status top bar directly in the corners
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // GPS Indicator and Fetch History on Top-Left
                    Column(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SignalStrengthIndicator(status = gpsStatus)
                            Column {
                                val isFix = gpsStatus.contains("Fix", ignoreCase = true)
                                val isSearching = gpsStatus.contains("Searching", ignoreCase = true)
                                val gpsDisplayLabel = when {
                                    isFix -> {
                                        val coords = gpsStatus.substringAfter("Fix:").trim()
                                        "GPS: FIX [$coords]"
                                    }
                                    isSearching -> "GPS: SEARCHING..."
                                    else -> "GPS: OFFLINE"
                                }
                                Text(
                                    text = gpsDisplayLabel.uppercase(),
                                    color = Color(0xFF94A3B8),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            FetchHistoryBars(fetchHistory = fetchHistory)
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(style = SpanStyle(color = Color(0xFF00FF88))) {
                                        append("OK:$okFetchCount")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF94A3B8))) {
                                        append(" | ")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFFFFB74D))) {
                                        append("DED:$deducedFetchCount")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF94A3B8))) {
                                        append(" | ")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFFFF5252))) {
                                        append("OFF:$offRoadFetchCount")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF94A3B8))) {
                                        append(" | ")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFFE040FB))) {
                                        append("TAG:$noLimitFetchCount")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF94A3B8))) {
                                        append(" | ")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF448AFF))) {
                                        append("RD:$noRoadsFetchCount")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF94A3B8))) {
                                        append(" | ")
                                    }
                                    withStyle(style = SpanStyle(color = Color(0xFF90A4AE))) {
                                        append("MAP:$noMapFetchCount")
                                    }
                                },
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Enlarge compass on Top-Right
                    CompassWidget(
                        bearing = bearing,
                        modifier = Modifier.size(80.dp)
                    )
                }
 
                // Speedometer Gauge Centered below indicators, lowered further by increasing top padding to decrease distance from bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SpeedometerGauge(
                        speed = speed,
                        speedColor = speedColor,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 48.dp)
                    )
                    Text(
                        text = limitStatus.uppercase(),
                        color = Color(0xFF64B5F6),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 0.dp)
                    )
                }
            }
 
            // 2. Speed Limit & Altitude Combined Panel (Full Width Row format)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1A0F172A))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF334155).copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: Altitude
                    Column {
                        Text(
                            "ALTITUDE (SRTM)",
                            color = Color(0xFF00FF88),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (elevation != null) "${elevation}m" else "---",
                            color = Color(0xFF00FF88),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = DigitalFontFamily
                        )
                    }
 
                    // Right side: Speed limit
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.clickable { showStatusLegend.value = true }
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            UrbanAreaHouseIcon(
                                isUrban = isUrbanArea && speedLimit != null,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            Text(
                                text = (if (speedLimit != null) {
                                    roadClass
                                } else {
                                    limitStatus
                                }).uppercase(),
                                color = if (overspeed) Color(0xFFFF0055) else Color.Gray,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (speedLimit == null) {
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = mapStatus.uppercase(),
                                    color = Color(0xFF00F0FF).copy(alpha = 0.8f),
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                        HolographicSpeedLimit(
                            limit = speedLimit,
                            currentFetchStatus = currentFetchStatus,
                            modifier = Modifier.size(80.dp)
                        )
                    }
                }
            }
 
            // 3. SEPARATED SPEED CHART (Full Width)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1A0F172A))
                    .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "SPEED HISTORICAL TIMELINE",
                        color = speedColor.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SteppedNeonChart(
                        data = speedHistory,
                        colors = speedColorHistory,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
 
            // 4. SEPARATED ALTITUDE CHART (Full Width)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1A0F172A))
                    .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "ALTITUDE HISTORICAL TIMELINE",
                        color = Color(0xFF00FF88).copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SteppedNeonChart(
                        data = elevationHistory,
                        colors = remember(elevationHistory.size) { elevationHistory.map { Color(0xFF00FF88) } },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
 
            }
 
            // 5. BOTTOM CONTROL CONSOLE
            SciFiBottomConsole(
                isMirror = isMirror,
                downloadProgress = downloadProgress,
                syncPhase = syncPhase,
                onToggleMirror = onToggleMirror,
                onDownloadData = onDownloadData
            )
        }
    }
}

@Composable
fun SpeedometerGauge(
    speed: Double,
    speedColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val DigitalFontFamily = remember {
        FontFamily(
            Font(path = "dseg7classic.ttf", assetManager = context.assets)
        )
    }

    val animatedSpeed by animateFloatAsState(
        targetValue = speed.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "animatedSpeed"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val paddingX = 20.dp.toPx()
            val paddingY = 20.dp.toPx()
            
            val a = (size.width - 2 * paddingX) / 2
            val b = (size.height - 2 * paddingY) / 2 + 28.dp.toPx()
            val shiftY = 12.dp.toPx()
            val center = Offset(paddingX + a, paddingY + b + shiftY)
            val arcSize = androidx.compose.ui.geometry.Size(a * 2, b * 2)
            val topLeft = Offset(paddingX, paddingY + shiftY)

            // Draw background ellipse arc
            drawArc(
                color = Color(0xFF1E293B),
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // Speed scale up to 170 km/h
            val maxSpeedScale = 170f
            val fillPercentage = (animatedSpeed / maxSpeedScale).coerceIn(0f, 1f)
            val activeSweep = fillPercentage * 240f
            
             // Outer glow effect backdrop
             drawArc(
                 brush = Brush.sweepGradient(
                     colors = listOf(
                         speedColor.copy(alpha = 0.05f),
                         speedColor.copy(alpha = 0.25f),
                         speedColor.copy(alpha = 0.25f)
                     ),
                     center = center
                 ),
                 startAngle = 150f,
                 sweepAngle = activeSweep,
                 useCenter = false,
                 topLeft = topLeft,
                 size = arcSize,
                 style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
             )

             // Active neon gauge ellipse arc
             drawArc(
                 brush = Brush.sweepGradient(
                     colors = listOf(
                         speedColor.copy(alpha = 0.3f),
                         speedColor,
                         speedColor
                     ),
                     center = center
                 ),
                 startAngle = 150f,
                 sweepAngle = activeSweep,
                 useCenter = false,
                 topLeft = topLeft,
                 size = arcSize,
                 style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
             )
 
             // Speedometer ticks aligned to the ellipse (0 to 170 km/h with 10 km/h intervals -> 18 ticks)
             // Drawn AFTER the active arc so they appear on top and can apply the inversion effect
             val tickCount = 18
             for (i in 0 until tickCount) {
                 val angleDeg = 150f + (i * (240f / (tickCount - 1)))
                 val angleRad = Math.toRadians(angleDeg.toDouble())
                 val speedVal = i * 10
                 val isProminent = (speedVal == 0 || speedVal == 50 || speedVal == 100 || speedVal == 150)
                 
                 // If the tick angle is within the swept angle of the active speed, it is covered
                 val isCovered = angleDeg <= 150f + activeSweep
                 
                 val tickLength = 6.dp.toPx()
                 val tickWidth = if (isProminent) 3.5.dp.toPx() else 1.8.dp.toPx()
                 
                 // Covered ticks use a dark semi-transparent color for inversion/stencil look
                 val tickColor = if (isCovered) {
                     Color.Black.copy(alpha = 0.5f)
                 } else {
                     if (isProminent) speedColor else speedColor.copy(alpha = 0.6f)
                 }
                 
                 val halfLen = tickLength / 2
                 val aStart = a - halfLen
                 val aEnd = a + halfLen
                 val bStart = b - halfLen
                 val bEnd = b + halfLen
 
                 val startX = center.x + aStart * Math.cos(angleRad).toFloat()
                 val startY = center.y + bStart * Math.sin(angleRad).toFloat()
                 val endX = center.x + aEnd * Math.cos(angleRad).toFloat()
                 val endY = center.y + bEnd * Math.sin(angleRad).toFloat()
                 
                 drawLine(
                     color = tickColor,
                     start = Offset(startX, startY),
                     end = Offset(endX, endY),
                     strokeWidth = tickWidth
                 )
             }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.wrapContentSize().offset(y = 42.dp)
        ) {
            Text(
                text = speed.roundToInt().toString(),
                color = speedColor,
                fontSize = 76.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = DigitalFontFamily,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "KM/H",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun HolographicSpeedLimit(
    limit: Int?,
    currentFetchStatus: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val DigitalFontFamily = remember {
        FontFamily(
            Font(path = "dseg7classic.ttf", assetManager = context.assets)
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "hologramRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val statusColor = when (currentFetchStatus) {
        1, 2 -> Color(0xFF00FF88) // Green (Map)
        6 -> Color(0xFFFFB74D) // Yellow (Deduced)
        3 -> Color(0xFFFF5252) // Light Red (Off-road)
        4 -> Color(0xFFE040FB) // Purple (No Tag)
        5 -> Color(0xFF448AFF) // Blue (No Roads)
        else -> Color(0xFF90A4AE) // Gray (No Map / Unknown)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width.coerceAtMost(size.height) / 2) - 6.dp.toPx()

            drawCircle(
                color = statusColor.copy(alpha = 0.1f),
                radius = radius,
                style = Stroke(width = 1.dp.toPx())
            )

            if (limit != null) {
                drawCircle(
                    color = Color(0xFFFF0055).copy(alpha = 0.2f),
                    radius = radius - 4.dp.toPx(),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = Color(0xFFFF0055),
                    radius = radius - 4.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )

                val tickCount = 12
                 for (i in 0 until tickCount) {
                     val angleDeg = rotationAngle + (i * (360f / tickCount))
                     val angleRad = Math.toRadians(angleDeg.toDouble())
                     val tickLen = 5.dp.toPx()
                     val startX = center.x + (radius + 2.dp.toPx()) * Math.cos(angleRad).toFloat()
                     val startY = center.y + (radius + 2.dp.toPx()) * Math.sin(angleRad).toFloat()
                     val endX = center.x + (radius + 2.dp.toPx() + tickLen) * Math.cos(angleRad).toFloat()
                     val endY = center.y + (radius + 2.dp.toPx() + tickLen) * Math.sin(angleRad).toFloat()

                     drawLine(
                         color = statusColor.copy(alpha = 0.8f),
                         start = Offset(startX, startY),
                         end = Offset(endX, endY),
                         strokeWidth = 1.5.dp.toPx()
                     )
                 }
            } else {
                drawCircle(
                    color = statusColor.copy(alpha = 0.2f),
                    radius = radius - 4.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )

                val tickCount = 8
                for (i in 0 until tickCount) {
                    val angleDeg = rotationAngle + (i * (360f / tickCount))
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val tickLen = 4.dp.toPx()
                    val startX = center.x + (radius - 4.dp.toPx()) * Math.cos(angleRad).toFloat()
                    val startY = center.y + (radius - 4.dp.toPx()) * Math.sin(angleRad).toFloat()
                    val endX = center.x + (radius - 4.dp.toPx() - tickLen) * Math.cos(angleRad).toFloat()
                    val endY = center.y + (radius - 4.dp.toPx() - tickLen) * Math.sin(angleRad).toFloat()

                    drawLine(
                        color = statusColor.copy(alpha = 0.6f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
            }
        }

        if (limit != null) {
            Text(
                text = limit.toString(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = DigitalFontFamily,
                textAlign = TextAlign.Center
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
    }
}

@Composable
fun UrbanAreaHouseIcon(
    isUrban: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isUrban) Color(0xFF00FF88) else Color(0xFF334155)
    val glowColor = if (isUrban) Color(0xFF00FF88).copy(alpha = 0.4f) else Color.Transparent
    
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            
            val roofPath = Path().apply {
                moveTo(w / 2f, h * 0.08f)
                lineTo(w * 0.08f, h * 0.45f)
                lineTo(w * 0.92f, h * 0.45f)
                close()
            }
            
            val bodyLeft = w * 0.18f
            val bodyTop = h * 0.45f
            val bodyWidth = w * 0.64f
            val bodyHeight = h * 0.45f
            
            if (isUrban) {
                drawPath(
                    path = roofPath,
                    color = glowColor,
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawRect(
                    color = glowColor,
                    topLeft = Offset(bodyLeft, bodyTop),
                    size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
            
            drawPath(
                path = roofPath,
                color = color
            )
            
            drawRect(
                color = color,
                topLeft = Offset(bodyLeft, bodyTop),
                size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight)
            )
            
            drawRect(
                color = Color(0xFF0F172A),
                topLeft = Offset(w / 2f - w * 0.08f, h - h * 0.25f - 1.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.25f)
            )
        }
    }
}

@Composable
fun SignalStrengthIndicator(
    status: String,
    modifier: Modifier = Modifier
) {
    val isFix = status.contains("Fix", ignoreCase = true)
    val isSearching = status.contains("Searching", ignoreCase = true)
    
    val activeColor = when {
        isFix -> Color(0xFF00FF88)
        isSearching -> Color(0xFFFFB74D)
        else -> Color(0xFFFF0055)
    }
    
    val barsCount = when {
        isFix -> 4
        isSearching -> 2
        else -> 1
    }
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..4) {
            val barHeight = (4 + (i * 3)).dp
            val isBarActive = i <= barsCount
            val barColor = if (isBarActive) activeColor else Color(0xFF1E293B)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
fun FetchHistoryBars(fetchHistory: List<FetchResult>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(
            items = fetchHistory,
            key = { it.id }
        ) { item ->
            val animatedHeight = remember { Animatable(0f) }
            LaunchedEffect(item.id) {
                animatedHeight.animateTo(12f, animationSpec = tween(300))
            }
            
            val color = when (item.status) {
                1 -> Color(0xFF00FF88) // Green (API Success)
                2 -> Color(0xFF00FF88) // Green (OK - Speed Limit Snapped)
                6 -> Color(0xFFFFB74D) // Yellow (OK - Speed Limit Deduced)
                3 -> Color(0xFFFF5252) // Light Red (Off-road > 50m)
                4 -> Color(0xFFE040FB) // Magenta/Purple (No Speed Tag)
                5 -> Color(0xFF448AFF) // Blue (No Roads Nearby)
                else -> Color(0xFF90A4AE) // Gray (No Map Loaded)
            }
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = animatedHeight.value.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
        val emptySlots = 20 - fetchHistory.size
        if (emptySlots > 0) {
            items(emptySlots) {
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF334155))
                )
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, name: String, desc: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 8.dp, height = 14.dp)
                .background(color)
        )
        Column {
            Text(
                text = name,
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = desc,
                color = Color.LightGray,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SciFiHeader(
    gpsStatus: String,
    mapStatus: String,
    isSimulatorActive: Boolean
) {
    val isFix = gpsStatus.contains("Fix", ignoreCase = true)
    val isSearching = gpsStatus.contains("Searching", ignoreCase = true)
    
    val gpsDisplayLabel = when {
        isFix -> {
            val coords = gpsStatus.substringAfter("Fix:").trim()
            "GPS: FIX [$coords]"
        }
        isSearching -> "GPS: SEARCHING..."
        else -> "GPS: OFFLINE"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.6f))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignalStrengthIndicator(status = gpsStatus)
            
            Column {
                Text(
                    text = gpsDisplayLabel.uppercase(),
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MAP: $mapStatus".uppercase(),
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusColor = if (isSimulatorActive) Color(0xFFFFB74D) else Color(0xFF00FF88)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isSimulatorActive) "SYS: SIMULATION" else "SYS: STABLE",
                color = statusColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}



@Composable
fun SciFiBottomConsole(
    isMirror: Boolean,
    downloadProgress: Float?,
    syncPhase: String?,
    onToggleMirror: () -> Unit,
    onDownloadData: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1A0F172A))
            .border(1.dp, Color(0xFF334155).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (downloadProgress != null || syncPhase != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val progressPercent = if (downloadProgress != null) "${(downloadProgress * 100).roundToInt()}%" else "..."
                    val label = when (syncPhase) {
                        "CONNECTING" -> "CONNECTING TO SERVERS..."
                        "DOWNLOADING" -> "DOWNLOADING DATA... $progressPercent"
                        "UNZIPPING" -> "UNZIPPING MAP DATA... $progressPercent"
                        "INITIALIZING" -> "INITIALIZING ENGINE..."
                        else -> "SYNCHRONIZING..."
                    }
                    Text(
                        text = label,
                        color = Color(0xFF00FF88),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(downloadProgress ?: 0f)
                                .fillMaxHeight()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(Color(0xFF00F0FF), Color(0xFF00FF88))
                                    )
                                )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mirror HUD control
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.6f))
                        .border(
                            width = 1.dp,
                            color = if (isMirror) Color(0xFF00F0FF) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onToggleMirror() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMirror) "MIRROR: ON" else "MIRROR: OFF",
                        color = if (isMirror) Color(0xFF00F0FF) else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }

                // Download Offline Data control
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.6f))
                        .border(
                            width = 1.dp,
                            color = if (downloadProgress != null || syncPhase != null) Color(0xFF00FF88) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = downloadProgress == null && syncPhase == null) { onDownloadData() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (downloadProgress != null || syncPhase != null) "SYNCING..." else "DOWNLOAD OFFLINE DATA",
                        color = if (downloadProgress != null || syncPhase != null) Color(0xFF00FF88) else Color(0xFF00FF88).copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun SteppedNeonChart(
    data: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // Futuristic fine grid lines
        val horizontalLines = 3
        for (i in 1..horizontalLines) {
            val yLine = (height / (horizontalLines + 1)) * i
            drawLine(
                color = Color(0xFF1E293B).copy(alpha = 0.3f),
                start = Offset(0f, yLine),
                end = Offset(width, yLine),
                strokeWidth = 1.dp.toPx()
            )
        }
        val verticalLines = 6
        for (i in 1..verticalLines) {
            val xLine = (width / (verticalLines + 1)) * i
            drawLine(
                color = Color(0xFF1E293B).copy(alpha = 0.3f),
                start = Offset(xLine, 0f),
                end = Offset(xLine, height),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (data.isEmpty()) return@Canvas

        val maxVal = (data.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val minVal = (data.minOrNull() ?: 0f).coerceAtMost(maxVal - 0.1f)
        val range = maxVal - minVal

        val stepX = if (data.size > 1) width / (data.size - 1) else width

        for (i in data.indices) {
            if (i > 0) {
                val prevX = (i - 1) * stepX
                val prevY = height - ((data[i - 1] - minVal) / range) * height
                val x = i * stepX
                val y = height - ((data[i] - minVal) / range) * height

                val segmentColor = if (i < colors.size) colors[i] else (colors.lastOrNull() ?: Color(0xFF00F0FF))

                // Draw neon glow filled area under this specific step segment
                val slicePath = Path().apply {
                    moveTo(prevX, prevY)
                    lineTo(x, prevY)
                    lineTo(x, y)
                    lineTo(x, height)
                    lineTo(prevX, height)
                    close()
                }
                val alphaVal = when (segmentColor.value) {
                    Color(0xFFFF0055).value -> 0.60f // Red (very dark luminance, needs strong alpha boost)
                    Color(0xFFFFB74D).value -> 0.45f // Yellow/Orange (medium-high luminance)
                    Color(0xFF00F0FF).value -> 0.50f // Blue/Cyan (medium luminance)
                    else -> 0.35f                    // Green (high luminance) / default
                }
                drawPath(
                    path = slicePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(segmentColor.copy(alpha = alphaVal), segmentColor.copy(alpha = 0.0f)),
                        startY = minOf(prevY, y),
                        endY = height
                    )
                )

                // Draw horizontal line segment
                drawLine(
                    color = segmentColor,
                    start = Offset(prevX, prevY),
                    end = Offset(x, prevY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Draw vertical line segment
                drawLine(
                    color = segmentColor,
                    start = Offset(x, prevY),
                    end = Offset(x, y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun CompassWidget(
    bearing: Float,
    modifier: Modifier = Modifier
) {
    val animatedBearing by animateFloatAsState(
        targetValue = bearing,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "animatedBearing"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0x1A0F172A))
            .border(1.5.dp, Color(0xFF334155).copy(alpha = 0.6f), CircleShape)
    ) {
        // Rotating compass card (NESW)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = -animatedBearing
                }
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.width.coerceAtMost(size.height) / 2
            val dialRadius = radius - 3.dp.toPx()

            // Draw outer dial ring
            drawCircle(
                color = Color(0xFF00F0FF).copy(alpha = 0.25f),
                radius = dialRadius,
                style = Stroke(width = 1.dp.toPx())
            )

            // Draw ticks every 30 degrees
            for (angle in 0 until 360 step 30) {
                val angleRad = Math.toRadians(angle.toDouble())
                val isPrincipal = angle % 90 == 0
                val tickLength = if (isPrincipal) 5.dp.toPx() else 3.dp.toPx()
                val tickColor = if (isPrincipal) Color(0xFF00F0FF) else Color(0xFF475569)
                val tickWidth = if (isPrincipal) 2.dp.toPx() else 1.dp.toPx()

                val startX = center.x + (dialRadius - tickLength) * Math.cos(angleRad).toFloat()
                val startY = center.y + (dialRadius - tickLength) * Math.sin(angleRad).toFloat()
                val endX = center.x + dialRadius * Math.cos(angleRad).toFloat()
                val endY = center.y + dialRadius * Math.sin(angleRad).toFloat()

                drawLine(
                    color = tickColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = tickWidth
                )
            }

            // Draw N, E, S, W text labels
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#00F0FF")
                    textSize = 8.sp.toPx()
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                // Standard coordinate angles (North is 270 deg, East is 0 deg, South is 90 deg, West is 180 deg)
                val labels = listOf("N" to 270f, "E" to 0f, "S" to 90f, "W" to 180f)
                for ((label, angle) in labels) {
                    val angleRad = Math.toRadians(angle.toDouble())
                    val textDist = dialRadius - 9.dp.toPx()
                    val x = center.x + textDist * Math.cos(angleRad).toFloat()
                    val y = center.y + textDist * Math.sin(angleRad).toFloat()

                    val fontMetrics = paint.fontMetrics
                    val yAdjusted = y - (fontMetrics.ascent + fontMetrics.descent) / 2

                    paint.color = if (label == "N") android.graphics.Color.parseColor("#FF0055") else android.graphics.Color.parseColor("#00F0FF")
                    drawText(label, x, yAdjusted, paint)
                }
            }
        }

        // Stationary header index/pointer (shows current heading pointing straight UP)
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val arrowLength = 9.dp.toPx()
            val arrowWidth = 6.dp.toPx()

            // Futuristic pointer arrow pointing UP
            val arrowPath = Path().apply {
                moveTo(center.x, center.y - arrowLength) // Tip
                lineTo(center.x - arrowWidth / 2, center.y - arrowLength / 3)
                lineTo(center.x, center.y - arrowLength / 2)
                lineTo(center.x + arrowWidth / 2, center.y - arrowLength / 3)
                close()
            }

            drawPath(
                path = arrowPath,
                color = Color(0xFF00FF88) // Sci-fi neon green pointer
            )

            // Draw a subtle center point indicator
            drawCircle(
                color = Color(0xFF00FF88).copy(alpha = 0.4f),
                radius = 2.dp.toPx()
            )
        }
    }
}


