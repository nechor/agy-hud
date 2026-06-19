package com.offlinehud.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MapMatchingEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var engine: MapMatchingEngine
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("files")
        engine = MapMatchingEngine(filesDir)
    }

    @Test
    fun testInitializationWithMissingDirectory() {
        val latch = CountDownLatch(1)
        var initResult = true

        engine.initialize { success ->
            initResult = success
            latch.countDown()
        }

        latch.await(2, TimeUnit.SECONDS)
        assertFalse("Initialization callback should receive false when graphhopper folder is missing", initResult)
        org.junit.Assert.assertTrue("Engine should be ready in mock mode", engine.isReady())
        org.junit.Assert.assertTrue("Engine should be in mock mode", engine.isMockMode())
        
        // Mock speed limit verification for Warsaw coordinates
        val limit = engine.getSpeedLimit(52.2297, 21.0122)
        org.junit.Assert.assertEquals(50.0, limit ?: 0.0, 0.01)
    }

    @Test
    fun testGetSpeedLimitWhenNotInitialized() {
        val limit = engine.getSpeedLimit(52.2297, 21.0122)
        assertNull("Speed limit should be null when engine is not initialized", limit)
    }
}
