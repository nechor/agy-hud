package com.offlinehud.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

class SrtmElevationProvider(private val baseDir: File) {
    companion object {
        private const val TAG = "SrtmElevationProvider"
    }

    private val cacheSize = 50
    private val elevationCache = object : java.util.LinkedHashMap<Pair<Double, Double>, Short?>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Pair<Double, Double>, Short?>?): Boolean {
            return size > cacheSize
        }
    }

    /**
     * Returns the elevation in meters for a given latitude and longitude.
     * Looks up files in the app's filesDir/srtm/ directory.
     */
    fun getElevation(latitude: Double, longitude: Double): Short? {
        val keyLat = (latitude * 100000.0).toInt() / 100000.0
        val keyLon = (longitude * 100000.0).toInt() / 100000.0
        val cacheKey = Pair(keyLat, keyLon)

        synchronized(elevationCache) {
            if (elevationCache.containsKey(cacheKey)) {
                return elevationCache[cacheKey]
            }
        }

        val latFloor = floor(latitude).toInt()
        val lonFloor = floor(longitude).toInt()

        val latPrefix = if (latFloor >= 0) "N" else "S"
        val latString = String.format("%s%02d", latPrefix, abs(latFloor))

        val lonPrefix = if (lonFloor >= 0) "E" else "W"
        val lonString = String.format("%s%03d", lonPrefix, abs(lonFloor))

        val fileName = "$latString$lonString.hgt"
        val srtmDir = File(baseDir, "srtm")
        val file = File(srtmDir, fileName)

        var result: Short? = null

        if (file.exists()) {
            val fileSize = file.length()
            val gridSize = when (fileSize) {
                2884802L -> 1201 // SRTM-3 (3 arc-second)
                25934402L -> 3601 // SRTM-1 (1 arc-second)
                else -> null
            }

            if (gridSize != null) {
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        val dLat = latitude - latFloor
                        val row = floor((1.0 - dLat) * (gridSize - 1)).toInt()

                        val dLon = longitude - lonFloor
                        val col = floor(dLon * (gridSize - 1)).toInt()

                        if (row in 0 until gridSize && col in 0 until gridSize) {
                            val offset = ((row * gridSize) + col) * 2L
                            raf.seek(offset)
                            val buffer = ByteArray(2)
                            raf.readFully(buffer)

                            val wrapped = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
                            val elevation = wrapped.short

                            if (elevation != Short.MIN_VALUE) {
                                result = elevation
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading SRTM elevation for $latitude, $longitude", e)
                }
            } else {
                Log.e(TAG, "Unsupported SRTM file size: $fileSize for $fileName")
            }
        } else {
            Log.w(TAG, "SRTM file not found: ${file.absolutePath}")
        }

        synchronized(elevationCache) {
            elevationCache[cacheKey] = result
        }

        return result
    }
}
