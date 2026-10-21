package com.zga.meridianatlas.viewer

import com.google.android.filament.MaterialInstance
import kotlin.math.pow

/** Display materials, not measured tissue optics or reconstructed skin detail. */
internal object AnatomyAppearance {
    internal data class Tissue(val srgb: Int, val roughness: Float, val metallic: Float = 0f) {
        /** glTF baseColorFactor and Filament float parameters are linear, rather than display RGB. */
        fun color(alpha: Float = 1f): FloatArray = floatArrayOf(
            AnatomyAppearance.linear((srgb shr 16) and 0xff), AnatomyAppearance.linear((srgb shr 8) and 0xff),
            AnatomyAppearance.linear(srgb and 0xff), alpha)

        fun applyTo(instance: MaterialInstance, alpha: Float = 1f) {
            val color = color(alpha)
            instance.setParameter("baseColorFactor", color[0], color[1], color[2], color[3])
            // Our controlled glTF metallic/roughness materials (and cutaway material) declare
            // all three parameters. Filament 1.78's JNI-created MaterialInstance wrappers do
            // not retain a Java Material back-reference; native-handle setters still work.
            instance.setParameter("roughnessFactor", roughness)
            instance.setParameter("metallicFactor", metallic)
        }
    }

    // Natural warm skin; warm ivory bone; neutral/cool connective tissue. All are dielectrics.
    private val tissues = mapOf(
        "skin" to Tissue(0xCB947C, .56f),
        "bone" to Tissue(0xE9E4D9, .58f),
        "connective" to Tissue(0xC3CCD0, .42f),
    )

    fun forMaterialName(name: String?): Tissue? = tissues[name]

    private fun linear(channel: Int): Float {
        val value = channel / 255.0
        return (if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)).toFloat()
    }
}
