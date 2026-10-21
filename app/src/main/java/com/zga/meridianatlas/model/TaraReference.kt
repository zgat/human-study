package com.zga.meridianatlas.model

/** A separate, verbatim TARA source record; never a translated or clinical-review result. */
data class TaraReference(
    val label: String,
    val sourceFile: String,
    /** Physical start line of the named subject in the original, unmodified Turtle file. */
    val rowNumber: Int,
    val fields: Map<String, List<String>>,
    val sourceId: String = ""
) {
    init {
        require(fields.keys.all { it in DISPLAY_FIELDS }) { "Unsupported TARA display field" }
    }

    companion object {
        /** Explicit allowlist: procedure, extraction and curator columns are not exposed. */
        val DISPLAY_FIELDS: Set<String> = setOf(
            "Label", "Pinyin Label", "Chinese Label", "Synonym", "Location",
            "Indications", "Vasculature", "Innervation", "Reference"
        )
    }
}
