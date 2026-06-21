import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.VehicleAccess
import com.graphhopper.routing.ev.VehicleSpeed
import com.graphhopper.util.EdgeIteratorState
import java.io.File

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

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: MapCompiler <input.osm.pbf> <output-dir>")
        return
    }
    val osmFilePath = args[0]
    val graphLocation = args[1]

    println("Starting compilation of $osmFilePath into $graphLocation...")
    
    val hopper = AndroidGraphHopper().apply {
        osmFile = osmFilePath
        graphHopperLocation = graphLocation
        profiles = listOf(Profile("car").setVehicle("car").setWeighting("custom"))
    }
    
    hopper.importOrLoad()
    hopper.close()
    
    println("Compilation completed successfully!")
}
