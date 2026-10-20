package com.zga.meridianatlas.viewer

import com.zga.meridianatlas.model.ProjectionAnchor
import com.zga.meridianatlas.model.Vec3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Actual shipped skin and route coordinates, independent of an Android renderer/device. */
class SurfaceRouteAssetTest {
    @Test fun shippedFrontAndBackRoutesConformToSkinWithoutDisablingFarSideOcclusion() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val meshBytes = File(assets, "models/skin-occlusion.bin").readBytes()
        val skin = meshBytes.inputStream().use(SkinOccluder::readFrom)
        val buffer = ByteBuffer.wrap(meshBytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(8) }
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        while (buffer.hasRemaining()) {
            buffer.float; buffer.float
            val z = buffer.float
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        val centerZ = (minZ+maxZ)*.5f
        val json = File(assets, "content/meridians.json").readText()
        val paths = Regex(""""id"\s*:\s*"((?:LU|SP|BL)-(?:left|right)-\d+)"\s*,\s*"side"\s*:\s*"[^"]+"\s*,\s*"internal"\s*:\s*false\s*,\s*"points"\s*:\s*\[(.*?)\]\s*,\s*"reviewStatus"""",
            RegexOption.DOT_MATCHES_ALL).findAll(json).toList()
        assertTrue("Real surface route branches must be parsed", paths.size >= 8)
        val number = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
        val eyeFront = Vec3(0f,.9f,3f)
        val eyeBack = Vec3(0f,.9f,-3f)
        val measurements = mutableMapOf<String, IntArray>()
        val rows = paths.map { path ->
            val id = path.groupValues[1]
            val values = number.findAll(path.groupValues[2]).map { it.value.toFloat() }.toList()
            assertEquals(0, values.size % 3)
            val controls = values.chunked(3).map { Vec3(it[0],it[1],it[2]) }
            val original = RouteCurveSampler.sample(controls).mapIndexed { index, point ->
                ProjectionAnchor("route:$id|0|$index", point)
            }
            val prepared = SurfaceRouteProjector(skin).conform(original)
            val frontBefore = original.count { skin.isOccluded(eyeFront, it.position) }
            val frontAfter = prepared.count { skin.isOccluded(eyeFront, it.position) }
            val backBefore = original.count { skin.isOccluded(eyeBack, it.position) }
            val backAfter = prepared.count { skin.isOccluded(eyeBack, it.position) }
            val oldFrontVisible = original.count {
                val p=it.position
                val facing=p.x*.15f*(eyeFront.x-p.x)+(p.z-centerZ)*(eyeFront.z-p.z) >= 0f
                facing && !skin.isOccluded(eyeFront,p)
            }
            val oldBackVisible = original.count {
                val p=it.position
                val facing=p.x*.15f*(eyeBack.x-p.x)+(p.z-centerZ)*(eyeBack.z-p.z) >= 0f
                facing && !skin.isOccluded(eyeBack,p)
            }
            val surfaceMatches = prepared.count { point ->
                skin.closestSurface(point.position, .00001f) != null
            }
            assertEquals("All $id display samples must reach real skin", prepared.size, surfaceMatches)
            // Every snap is display-only; exact original controls remain in the source samples.
            assertTrue(original.map { it.position }.containsAll(controls))
            measurements[id] = intArrayOf(prepared.size, frontBefore, frontAfter, backBefore, backAfter,
                oldFrontVisible, oldBackVisible)
            "{\"path\":\"$id\",\"samples\":${prepared.size},\"surfaceMatches\":$surfaceMatches," +
                "\"frontOccludedBefore\":$frontBefore,\"frontOccludedAfter\":$frontAfter," +
                "\"backOccludedBefore\":$backBefore,\"backOccludedAfter\":$backAfter," +
                "\"frontVisibleAfter\":${prepared.size-frontAfter},\"backVisibleAfter\":${prepared.size-backAfter}," +
                "\"frontVisibleWithOldRadialNormal\":$oldFrontVisible,\"backVisibleWithOldRadialNormal\":$oldBackVisible}"
        }
        val spleen = measurements.filterKeys { it.startsWith("SP-") }.values
        assertTrue("Front SP interpolation should no longer sink into skin",
            spleen.sumOf { it[1]-it[2] } >= 100)
        assertEquals("The same SP samples must stay hidden from behind",
            spleen.sumOf { it[0] }, spleen.sumOf { it[4] })
        val bladderTrunk = measurements.filterKeys { it.startsWith("BL-") &&
            (it.endsWith("-2") || it.endsWith("-3")) }.values
        assertEquals("Posterior BL trunk is visible from behind", 0, bladderTrunk.sumOf { it[4] })
        assertEquals("Posterior BL trunk does not bleed through the front",
            bladderTrunk.sumOf { it[0] }, bladderTrunk.sumOf { it[2] })
        val lung = measurements.filterKeys { it.startsWith("LU-") }.values
        assertTrue("Lung routes stay hidden on the far arm/back",
            lung.sumOf { it[4] } > lung.sumOf { it[0] }*.9)
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val report = "{\"skin\":\"models/skin-occlusion.bin\",\"skinSha256\":\"${sha(meshBytes)}\"," +
            "\"skinTriangles\":${ByteBuffer.wrap(meshBytes).order(ByteOrder.LITTLE_ENDIAN).getInt(4)}," +
            "\"meridiansSha256\":\"${sha(json.toByteArray())}\",\"units\":\"metres\"," +
            "\"eyeFront\":[0,0.9,3],\"eyeBack\":[0,0.9,-3]," +
            "\"nearSurfaceTolerance\":0.008,\"surfaceSearchLimit\":0.05," +
            "\"displaySampleSpacing\":0.006,\"maxStepsPerSegment\":128,\"paths\":[${rows.joinToString(",") }]}\n"
        File("build/reports/surface-route-geometry.json").apply { parentFile?.mkdirs(); writeText(report) }
        println(report)
    }
}
