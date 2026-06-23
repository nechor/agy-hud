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
        org.junit.Assert.assertEquals(50.0, speedLimit!!.limit, 0.1)
    }

    @Test
    fun testQueryPerformanceOnCompiledGraph() {
        val zipFile = File("../graphhopper.zip")
        if (!zipFile.exists()) {
            println("Skipping benchmark: graphhopper.zip not found at project root")
            return
        }
        
        val targetDir = tempFolder.newFolder("unzipped_graph")
        unzip(zipFile, targetDir)
        
        val testEngine = MapMatchingEngine(targetDir)
        val latch = CountDownLatch(1)
        var initSuccess = false
        testEngine.initialize { success ->
            initSuccess = success
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        
        org.junit.Assert.assertTrue("Compiled graph should initialize successfully", initSuccess)
        
        val coords = listOf(
            Pair(53.7784, 20.4801),
            Pair(53.7795, 20.4815),
            Pair(53.7750, 20.4780),
            Pair(53.7810, 20.4900),
            Pair(53.7700, 20.4600)
        )
        
        for (coord in coords) {
            testEngine.getSpeedLimit(coord.first, coord.second)
        }
        
        val iterations = 1000
        val startTime = System.nanoTime()
        var matchedCount = 0
        for (i in 0 until iterations) {
            val coord = coords[i % coords.size]
            val limit = testEngine.getSpeedLimit(coord.first, coord.second)
            if (limit != null) {
                matchedCount++
            }
        }
        val endTime = System.nanoTime()
        val totalTimeMs = (endTime - startTime) / 1_000_000.0
        val avgQueryTimeUs = ((endTime - startTime) / 1_000.0) / iterations
        
        println("=== GraphHopper Benchmark Results ===")
        println("Processed $iterations queries in ${String.format("%.2f", totalTimeMs)} ms")
        println("Average query time: ${String.format("%.2f", avgQueryTimeUs)} us (microseconds)")
        println("Road snap success rate: ${matchedCount * 100 / iterations}%")
        println("=====================================")
        
        org.junit.Assert.assertTrue("Average query time should be under 5ms (5000us)", avgQueryTimeUs < 5000.0)
    }

    private fun runSpeedLimitDeductionTest(
        highway: String,
        name: String,
        oneway: String?,
        maxspeed: String?,
        lat: Double = 52.2297,
        lon: Double = 21.0122,
        queryLat: Double = 52.2298,
        queryLon: Double = 21.0125
    ): SpeedLimitResult? {
        val onewayTag = if (oneway != null) "<tag k=\"oneway\" v=\"$oneway\"/>" else ""
        val maxspeedTag = if (maxspeed != null) "<tag k=\"maxspeed\" v=\"$maxspeed\"/>" else ""
        val osmContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6" generator="Mock Source">
              <node id="1" lat="$lat" lon="$lon"/>
              <node id="2" lat="${lat + 0.0003}" lon="${lon + 0.0008}"/>
              <way id="3">
                <nd ref="1"/>
                <nd ref="2"/>
                <tag k="highway" v="$highway"/>
                <tag k="name" v="$name"/>
                $onewayTag
                $maxspeedTag
              </way>
            </osm>
        """.trimIndent()
        
        val testFolder = tempFolder.newFolder()
        val osmFile = File(testFolder, "temp_test_map.osm")
        osmFile.writeText(osmContent)
        
        val testEngine = MapMatchingEngine(testFolder)
        val latch = CountDownLatch(1)
        testEngine.importOsmFile(osmFile) { _ ->
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        
        val result = testEngine.getSpeedLimit(queryLat, queryLon)
        testEngine.close()
        return result
    }

    @Test
    fun testPolishSpeedLimitDeductionRules() {
        // 1. Zone of residence
        val livingStreet = runSpeedLimitDeductionTest("living_street", "Ulica Kwiatowa", null, null)
        org.junit.Assert.assertNotNull(livingStreet)
        org.junit.Assert.assertEquals(20.0, livingStreet!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, livingStreet.status)
        org.junit.Assert.assertTrue(livingStreet.isUrban)

        // 2. Motorway (Autostrada)
        val motorway = runSpeedLimitDeductionTest("motorway", "Autostrada A1", "yes", null)
        org.junit.Assert.assertNotNull(motorway)
        org.junit.Assert.assertEquals(140.0, motorway!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, motorway.status)
        org.junit.Assert.assertFalse(motorway.isUrban)

        // 3. Dual carriageway expressway (S7, oneway=yes)
        val expDual = runSpeedLimitDeductionTest("trunk", "S7", "yes", null)
        org.junit.Assert.assertNotNull(expDual)
        org.junit.Assert.assertEquals(120.0, expDual!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, expDual.status)
        org.junit.Assert.assertFalse(expDual.isUrban)

        // 4. Single carriageway expressway (S1, oneway=no)
        val expSingle = runSpeedLimitDeductionTest("trunk", "S1", "no", null)
        org.junit.Assert.assertNotNull(expSingle)
        org.junit.Assert.assertEquals(100.0, expSingle!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, expSingle.status)
        org.junit.Assert.assertFalse(expSingle.isUrban)

        // 5. Dual carriageway national road (DK7, oneway=yes, non-urban)
        val dkDual = runSpeedLimitDeductionTest("trunk", "DK7", "yes", null)
        org.junit.Assert.assertNotNull(dkDual)
        org.junit.Assert.assertEquals(100.0, dkDual!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, dkDual.status)
        org.junit.Assert.assertFalse(dkDual.isUrban)

        // 6. Single carriageway national road (DK7, oneway=no, non-urban)
        val dkSingle = runSpeedLimitDeductionTest("trunk", "DK7", "no", null)
        org.junit.Assert.assertNotNull(dkSingle)
        org.junit.Assert.assertEquals(90.0, dkSingle!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, dkSingle.status)
        org.junit.Assert.assertFalse(dkSingle.isUrban)

        // 7. National road inside urban area (DK7, oneway=no, urbanName matches like "Ulica Warszawska" -> isLikelyUrban)
        val dkUrban = runSpeedLimitDeductionTest("trunk", "Ulica Warszawska", "no", null)
        org.junit.Assert.assertNotNull(dkUrban)
        org.junit.Assert.assertEquals(50.0, dkUrban!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, dkUrban.status)
        org.junit.Assert.assertTrue(dkUrban.isUrban)

        // 8. Primary road, dual carriageway (non-urban)
        val primaryDual = runSpeedLimitDeductionTest("primary", "DK92", "yes", null)
        org.junit.Assert.assertNotNull(primaryDual)
        org.junit.Assert.assertEquals(100.0, primaryDual!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, primaryDual.status)
        org.junit.Assert.assertFalse(primaryDual.isUrban)

        // 9. Primary road, single carriageway (non-urban)
        val primarySingle = runSpeedLimitDeductionTest("primary", "DK92", "no", null)
        org.junit.Assert.assertNotNull(primarySingle)
        org.junit.Assert.assertEquals(90.0, primarySingle!!.limit, 0.1)
        org.junit.Assert.assertEquals(6, primarySingle.status)
        org.junit.Assert.assertFalse(primarySingle.isUrban)

        // 10. Explicit sign (maxspeed=70)
        val explicitSign = runSpeedLimitDeductionTest("primary", "Ulica Gdyńska", "no", "70")
        org.junit.Assert.assertNotNull(explicitSign)
        org.junit.Assert.assertEquals(70.0, explicitSign!!.limit, 0.1)
        org.junit.Assert.assertEquals(2, explicitSign.status)
    }

    private fun unzip(zipFile: File, targetDirectory: File) {
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
            var entry: java.util.zip.ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(targetDirectory, entry!!.name)
                val dir = if (entry!!.isDirectory) file else file.parentFile
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw java.io.IOException("Failed to ensure directory: ${dir.absolutePath}")
                }
                if (entry!!.isDirectory) continue
                java.io.BufferedOutputStream(java.io.FileOutputStream(file)).use { dest ->
                    val buffer = ByteArray(8192)
                    var count: Int
                    while (zis.read(buffer).also { count = it } != -1) {
                        dest.write(buffer, 0, count)
                    }
                }
            }
        }
    }
}
