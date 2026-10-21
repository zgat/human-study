package com.zga.meridianatlas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zga.meridianatlas.model.TaraReference

/** A separate reader for the publisher's unchanged text, without clinical procedure fields. */
@Composable internal fun TaraReferencePanel(pointId: String, references: List<TaraReference>, onLicense: () -> Unit) {
    var expanded by remember(pointId) { mutableStateOf(false) }
    val accent = Color(0xff58d8c5)
    val muted = Color(0xffa6bac8)
    val uriHandler = LocalUriHandler.current
    var sourceUnavailable by remember(pointId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TARA 穴位资料（英文原文）", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (references.isEmpty()) {
            Text("当前 TARA 版本未匹配到本条目，保留上方中文资料。", color = muted, fontSize = 12.sp)
        } else {
            Text("原文独立展示；TARA 的编号体系可能与本应用国标不同。", color = muted, fontSize = 12.sp)
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起 TARA 原文" else "展开 TARA 原文")
            }
            if (expanded) {
                references.forEach { record ->
                    Text("TARA · ${record.label}", color = accent, fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TARA_READING_ORDER.forEach { field ->
                                val values = record.fields[field].orEmpty()
                                if (values.isEmpty()) return@forEach
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(taraFieldTitle(field), color = muted, fontSize = 12.sp)
                                    values.forEach { text ->
                                        Text(text, fontSize = 14.sp, lineHeight = 22.sp, color = Color(0xffcfdee7))
                                    }
                                }
                            }
                            Text("${record.sourceFile} · 原文起始行 ${record.rowNumber}", color = muted, fontSize = 11.sp)
                        }
                    }
                }
                Text("TARA Acupoints Ontology 1.7.2 · Fahim T. Imam et al.\n原始发布文件 7c05c840 · CC BY-NC-ND 4.0", color = muted, fontSize = 11.sp)
                Text("原文字段中若有拼写或转录问题，保持原样；传统主治资料不等同于现代疗效结论。", color = muted, fontSize = 11.sp)
                OutlinedButton(onClick = onLicense) { Text("TARA 来源与许可") }
                OutlinedButton(onClick = {
                    runCatching { uriHandler.openUri(TARA_SOURCE_URL) }.onFailure { sourceUnavailable = true }
                }) { Text("打开 TARA 上游数据") }
                if (sourceUnavailable) SelectionContainer {
                    Text(TARA_SOURCE_URL, color = muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

// Select original fields for reading; individual source literals remain unchanged.
private val TARA_READING_ORDER = listOf("Chinese Label", "Pinyin Label", "Synonym", "Location",
    "Indications", "Vasculature", "Innervation", "Reference")

private const val TARA_SOURCE_URL = "https://github.com/SciCrunch/TARA-Ontology-Repository/blob/7c05c8407f6e529bc839d34109e67ab00c642c73/docs/distribution/ontology/version/1.7.2/asserted/acupoints.ttl"

private fun taraFieldTitle(field: String): String = when (field) {
    "Label" -> "标准编号或标签（原文）"
    "Chinese Label" -> "中文名称（原字段）"
    "Pinyin Label" -> "拼音（原字段）"
    "Synonym" -> "别名与原编号（原文）"
    "Meridian" -> "归经（原文）"
    "WHO Location" -> "WHO 定位（英文原文）"
    "Location" -> "定位（英文原文）"
    "Indications" -> "传统主治（英文原文）"
    "Vasculature" -> "血管关系（英文原文）"
    "Innervation" -> "神经关系（英文原文）"
    "Reference-WHO" -> "WHO 出处（原文）"
    "Reference-CAM" -> "CAM 出处（原文）"
    "Reference" -> "出处（原文）"
    "Reference-Synonym" -> "别名出处（原文）"
    else -> field
}
