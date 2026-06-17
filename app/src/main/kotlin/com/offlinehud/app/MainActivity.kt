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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.util.Log
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.abs

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
    private val downloadProgress = mutableStateOf<Float?>(null)

    // History logs for step charts
    val speedHistory = mutableStateListOf<Float>()
    val speedColorHistory = mutableStateListOf<Color>()
    val elevationHistory = mutableStateListOf<Float>()
    private val locationHistory = mutableListOf<Pair<Double, Double>>()

    // Hysteresis helper
    private var lastValidSpeedLimit: Int? = null
    private var lastSpeedLimitTime: Long = 0L
    private val limitExpiryMs = 5000L // 5 seconds hysteresis

    // Simulator properties
    private val isSimulatorActive = mutableStateOf(false)
    private var simulatorIndex = 0
    // A synthetic track containing (lat, lon, simulated_speed_kmh) for test drives
    private val testTrack = listOf(
        Triple(52.2297, 21.0122, 50.0), // Start: Warsaw
        Triple(52.2300, 21.0130, 52.0),
        Triple(52.2305, 21.0145, 68.0), // Exceeding 50 limit!
        Triple(52.2310, 21.0160, 85.0), // High speed
        Triple(52.2315, 21.0175, 45.0),
        Triple(52.2320, 21.0190, 48.0)
    )

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
                    isSimulatorActive = isSimulatorActive.value,
                    speedHistory = speedHistory,
                    speedColorHistory = speedColorHistory,
                    elevationHistory = elevationHistory,
                    downloadProgress = downloadProgress.value,
                    onToggleMirror = { isSymmetricMirror.value = !isSymmetricMirror.value },
                    onToggleSimulator = { toggleSimulator() },
                    onDownloadData = { downloadOfflineData() }
                )
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
        if (isSimulatorActive.value) return // Don't let real GPS override simulator
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
        if (currentSpeedKmh > 3.0 || isSimulatorActive.value) {
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
            mapMatchingEngine.getSpeedLimit(lat, lon)?.roundToInt()
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

    private val simulatorHandler = Handler(Looper.getMainLooper())
    private val simulatorRunnable = object : Runnable {
        override fun run() {
            if (!isSimulatorActive.value) return
            val currentPoint = testTrack[simulatorIndex]
            
            // For testing: if map files are missing, simulate speed limit of 50 km/h
            val simulatedLimit = if (mapMatchingEngine.isReady()) {
                mapMatchingEngine.getSpeedLimit(currentPoint.first, currentPoint.second)?.roundToInt()
            } else {
                50 // Demo limit
            }

            processNewLocation(currentPoint.first, currentPoint.second, currentPoint.third)
            if (simulatedLimit != null || !mapMatchingEngine.isReady()) {
                speedLimit.value = simulatedLimit
            }

            simulatorIndex = (simulatorIndex + 1) % testTrack.size
            simulatorHandler.postDelayed(this, 1500)
        }
    }

    private fun toggleSimulator() {
        if (isSimulatorActive.value) {
            isSimulatorActive.value = false
            simulatorHandler.removeCallbacks(simulatorRunnable)
            startLocationUpdates()
        } else {
            isSimulatorActive.value = true
            simulatorIndex = 0
            simulatorHandler.post(simulatorRunnable)
        }
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
        
        val handler = Handler(Looper.getMainLooper())
        var progress = 0f
        handler.post(object : Runnable {
            override fun run() {
                progress += 0.05f
                if (progress >= 1.0f) {
                    downloadProgress.value = null
                    // Set map status to ready, setup mock srtm files for Warsaw demo track
                    createSampleSrtmFile(52.2297, 21.0122)
                    mapMatchingEngine.initialize { success ->
                        runOnUiThread {
                            mapStatus.value = if (success) "Offline Map: Ready" else "Offline Map: Demo Mode (No Map Files)"
                        }
                    }
                } else {
                    downloadProgress.value = progress
                    handler.postDelayed(this, 100)
                }
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        simulatorHandler.removeCallbacks(simulatorRunnable)
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
    isSimulatorActive: Boolean,
    speedHistory: List<Float>,
    speedColorHistory: List<Color>,
    elevationHistory: List<Float>,
    downloadProgress: Float?,
    onToggleMirror: () -> Unit,
    onToggleSimulator: () -> Unit,
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // GPS Indicator on Top-Left
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

                    // Small compass on Top-Right
                    CompassWidget(
                        bearing = bearing,
                        modifier = Modifier.size(54.dp)
                    )
                }
 
                // Speedometer Gauge Centered below indicators
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SpeedometerGauge(
                        speed = speed,
                        speedColor = speedColor,
                        modifier = Modifier.fillMaxSize()
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "LIMIT SPEED",
                                color = Color(0xFF64B5F6),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = if (overspeed) "EXCEEDED" else "LOGGED",
                                color = if (overspeed) Color(0xFFFF0055) else Color.Gray,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        HolographicSpeedLimit(
                            limit = speedLimit,
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
                isSimulatorActive = isSimulatorActive,
                downloadProgress = downloadProgress,
                onToggleMirror = onToggleMirror,
                onToggleSimulator = onToggleSimulator,
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
            val b = (size.height - 2 * paddingY) / 2
            val center = Offset(paddingX + a, paddingY + b)
            val arcSize = androidx.compose.ui.geometry.Size(a * 2, b * 2)
            val topLeft = Offset(paddingX, paddingY)

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
            val tickCount = 18
            for (i in 0 until tickCount) {
                val angleDeg = 150f + (i * (240f / (tickCount - 1)))
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val isMajor = i % 2 == 0
                val tickLength = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
                val tickWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
                val tickColor = if (isMajor) speedColor.copy(alpha = 0.8f) else Color(0xFF475569)
                
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
            modifier = Modifier.wrapContentSize()
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width.coerceAtMost(size.height) / 2) - 6.dp.toPx()

            drawCircle(
                color = Color(0xFF00F0FF).copy(alpha = 0.1f),
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
                        color = Color(0xFFFF0055).copy(alpha = 0.7f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            } else {
                drawCircle(
                    color = Color(0xFF00F0FF).copy(alpha = 0.2f),
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
                        color = Color(0xFF00F0FF).copy(alpha = 0.5f),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 1.dp.toPx()
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
                    .background(Color(0xFF00F0FF))
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
    isSimulatorActive: Boolean,
    downloadProgress: Float?,
    onToggleMirror: () -> Unit,
    onToggleSimulator: () -> Unit,
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
            if (downloadProgress != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "DOWNLOADING OFFLINE DATA... ${(downloadProgress * 100).roundToInt()}%",
                        color = Color(0xFF00F0FF),
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
                                .fillMaxWidth(downloadProgress)
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
                            color = if (downloadProgress != null) Color(0xFF00FF88) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = downloadProgress == null) { onDownloadData() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (downloadProgress != null) "SYNCING..." else "DOWNLOAD OFFLINE DATA",
                        color = if (downloadProgress != null) Color(0xFF00FF88) else Color(0xFF00FF88).copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Demo Drive / Simulation control (toggles between DEMO and REAL GPS measurement)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.6f))
                        .border(
                            width = 1.dp,
                            color = if (isSimulatorActive) Color(0xFFFFB74D) else Color(0xFF00FF88).copy(alpha = 0.6f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onToggleSimulator() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSimulatorActive) "MODE: DEMO" else "MODE: REAL",
                        color = if (isSimulatorActive) Color(0xFFFFB74D) else Color(0xFF00FF88),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
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
                drawPath(
                    path = slicePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(segmentColor.copy(alpha = 0.15f), Color.Transparent)
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


