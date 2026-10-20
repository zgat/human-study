package com.zga.meridianatlas.model

import java.text.Normalizer

object SearchIndex {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[\\s'’\\-]"), "")
    fun matches(query: String, vararg values: String): Boolean {
        val needle = normalize(query)
        return needle.isBlank() || values.any { normalize(it).contains(needle) }
    }
}
