package com.offlinehud.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SrtmElevationProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var srtmProvider: SrtmElevationProvider
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        srtmProvider = SrtmElevationProvider(filesDir)
    }

    @Test
    fun testGetElevationWithMissingFile() {
        // When file doesn't exist, it should return null
        val elevation = srtmProvider.getElevation(52.2297, 21.0122)
        assertNull(elevation)
    }

    @Test
    fun testGetElevationWithValidFile() {
        // Create filesDir/srtm directory
        val srtmDir = File(filesDir, "srtm").apply { mkdirs() }
        val hgtFile = File(srtmDir, "N52E021.hgt")

        // SRTM-3 file size: 1201 * 1201 * 2 = 2884802 bytes
        val buffer = ByteArray(2884802)
        
        // Let's set elevation of 150 meters at row 500, col 500
        val testLat = 52.583333
        val testLon = 21.416667
        
        val offset = ((500 * 1201) + 500) * 2
        buffer[offset] = 0
        buffer[offset + 1] = 150.toByte() // 150 meters

        hgtFile.writeBytes(buffer)

        val elevation = srtmProvider.getElevation(testLat, testLon)
        assertEquals(150.toShort(), elevation)
    }

    @Test
    fun testGetElevationWithVoidData() {
        val srtmDir = File(filesDir, "srtm").apply { mkdirs() }
        val hgtFile = File(srtmDir, "N52E021.hgt")
        val buffer = ByteArray(2884802)
        
        val testLat = 52.583333
        val testLon = 21.416667
        
        val offset = ((500 * 1201) + 500) * 2
        // Short.MIN_VALUE in big-endian is 0x80 0x00
        buffer[offset] = 0x80.toByte()
        buffer[offset + 1] = 0x00.toByte()

        hgtFile.writeBytes(buffer)

        val elevation = srtmProvider.getElevation(testLat, testLon)
        assertNull("Void value should return null", elevation)
    }

    @Test
    fun testGetElevationCache() {
        val srtmDir = File(filesDir, "srtm").apply { mkdirs() }
        val hgtFile = File(srtmDir, "N52E021.hgt")
        val buffer = ByteArray(2884802)
        
        val testLat = 52.583333
        val testLon = 21.416667
        val offset = ((500 * 1201) + 500) * 2
        buffer[offset] = 0
        buffer[offset + 1] = 120.toByte() // 120 meters
        hgtFile.writeBytes(buffer)

        // First call reads from file and caches it
        val elev1 = srtmProvider.getElevation(testLat, testLon)
        assertEquals(120.toShort(), elev1)

        // Delete file to verify subsequent calls read from cache and don't hit the disk
        hgtFile.delete()

        val elev2 = srtmProvider.getElevation(testLat, testLon)
        assertEquals("Should be fetched from cache after file deletion", 120.toShort(), elev2)
    }
}
