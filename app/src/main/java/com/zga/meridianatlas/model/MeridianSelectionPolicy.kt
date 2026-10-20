package com.zga.meridianatlas.model

/** Checkbox choices are independent of the temporary route context of a selected point. */
object MeridianSelectionPolicy {
    /** Changes only the supplied category, preserving choices in every other category. */
    fun setEnabled(current: Set<String>, ids: Set<String>, enabled: Boolean): Set<String> =
        if (enabled) current + ids else current - ids

    /** A highlighted point keeps its own route visible even when its checkbox is cleared. */
    fun effective(enabled: Set<String>, selectedMeridian: String?): Set<String> =
        enabled + setOfNotNull(selectedMeridian)
}
