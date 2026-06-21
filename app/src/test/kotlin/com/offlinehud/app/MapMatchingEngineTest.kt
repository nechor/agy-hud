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
        assertFalse("Engine should not be ready when graphhopper folder is missing", engine.isReady())
        org.junit.Assert.assertTrue("Engine should be in mock/not-ready mode", engine.isMockMode())
        
        val limit = engine.getSpeedLimit(52.2297, 21.0122)
        assertNull("Speed limit should be null when not initialized", limit)
    }

    @Test
    fun testGetSpeedLimitWhenNotInitialized() {
        val limit = engine.getSpeedLimit(52.2297, 21.0122)
        assertNull("Speed limit should be null when engine is not initialized", limit)
    }

    @Test
    fun testImportAndQuerySpeedLimit() {
        // Create a simple valid OSM XML content
        val osmContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="Mock Source">
              <node id="1" lat="52.2297" lon="21.0122"/>
              <node id="2" lat="52.2300" lon="21.0130"/>
              <way id="3">
                <nd ref="1"/>
                <nd ref="2"/>
                <tag k="highway" v="residential"/>
                <tag k="maxspeed" v="50"/>
              </way>
            </osm>
        """.trimIndent()
        
        val osmFile = tempFolder.newFile("test_map.osm")
        osmFile.writeText(osmContent)
        
        val latch = CountDownLatch(1)
        var importSuccess = false
        
        engine.importOsmFile(osmFile) { success ->
            importSuccess = success
            latch.countDown()
        }
        
        val completed = latch.await(10, TimeUnit.SECONDS)
        org.junit.Assert.assertTrue("Import should finish within 10 seconds", completed)
        org.junit.Assert.assertTrue("OSM XML compilation/import should be successful", importSuccess)
        org.junit.Assert.assertTrue("Engine should be ready/initialized", engine.isReady())
        
        // Query near the road way
        val speedLimit = engine.getSpeedLimit(52.2298, 21.0125)
        org.junit.Assert.assertNotNull("Speed limit should not be null near the roadway", speedLimit)
        org.junit.Assert.assertEquals(50.0, speedLimit!!, 0.1)
    }
}
