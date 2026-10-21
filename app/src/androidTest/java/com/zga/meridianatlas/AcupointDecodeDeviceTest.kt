package com.zga.meridianatlas

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zga.meridianatlas.data.decodePoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's real JSONObject rather than the JVM Android-stub defaults. */
@RunWith(AndroidJUnit4::class)
class AcupointDecodeDeviceTest {
    @Test fun optionalCoordinateNoticePreservesTheAuthoredChineseText() {
        val notice = "腋部模型限制：标准体位与当前底模不同。\n锚点用于教学观察，待专业审校。"
        val raw = JSONObject().put("id", "gb22").put("nameZh", "渊腋")
            .put("coordinateNoticeZh", notice).put("cunReviewZh", "头部前后发际尚待标定")
        val point = decodePoint(raw)
        assertEquals("gb22", point.id)
        assertEquals("渊腋", point.nameZh)
        assertEquals(notice, point.coordinateNoticeZh)
        assertEquals("头部前后发际尚待标定", point.cunReviewZh)
    }

    @Test fun olderEntriesAndExplicitJsonNullKeepTheEmptyNoticeDefault() {
        val legacy = JSONObject("""{"id":"lu1","nameZh":"中府"}""")
        assertEquals("", decodePoint(legacy).coordinateNoticeZh)
        assertEquals("", decodePoint(legacy).cunReviewZh)
        legacy.put("coordinateNoticeZh", JSONObject.NULL)
        assertEquals("", decodePoint(legacy).coordinateNoticeZh)
        assertEquals("", decodePoint(legacy).cunReviewZh)
    }
}
