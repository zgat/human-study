package com.zga.meridianatlas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zga.meridianatlas.data.AtlasRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasDataDeviceTest {
    @Test fun offlineRoomSeedPreservesStandardsAndSpatialInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AtlasRepository(context)
        val data = repository.load()
        assertEquals(413, data.points.size)
        assertEquals(362, data.points.count { it.kind == "meridian" })
        assertEquals(51, data.points.count { it.kind == "extra" })
        assertEquals(413, data.points.map { it.id }.toSet().size)
        assertTrue("Each point needs a sourced purpose description", data.points.all {
            it.traditionalIndicationsZh.isNotBlank() && it.functionSourceRefs.isNotEmpty() && it.functionReviewStatus.isNotBlank()
        })
        assertTrue(data.points.all { it.locationZh.isNotBlank() && it.instances.all { anchor -> anchor.position != null } })
        val yintang = data.points.single { it.nameZh == "印堂" }
        assertTrue("GV24+" in yintang.codes)
        assertEquals("GV", yintang.meridianId)
        assertEquals(4, data.points.single { it.nameZh == "四神聪" }.instances.size)
        assertEquals(34, data.points.single { it.nameZh == "夹脊" }.instances.size)
        val oral = data.points.single { it.nameZh == "金津玉液" }
        assertEquals(setOf("EX-HN12", "EX-HN13"), oral.codes.toSet())
        assertTrue(oral.instances.all { it.viewId == "oral" })
        assertEquals(20, data.meridians.size)
        assertEquals(20, data.meridians.map { it.color }.distinct().size)
        assertTrue(data.meridians.all { it.paths.isNotEmpty() && it.paths.all { path -> path.points.size >= 2 } })
        assertTrue(data.meridians.filter { it.id in setOf("CHONG", "DAI", "YINQIAO", "YANGQIAO", "YINWEI", "YANGWEI") }.all { it.pointIds.isNotEmpty() })
        assertEquals(data.points.map { it.id }, repository.load().points.map { it.id })
        assertTrue(data.structures.any { it.system == "skin" })
        assertTrue(data.structures.any { it.nameZh.contains("背阔肌") })
    }
}
