package com.zga.meridianatlas.viewer

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Copy each primitive's glTF color when replacing its material for sagittal clipping. */
internal object GlbBaseColors {
    fun read(source: ByteBuffer): Map<String, List<FloatArray>> {
        val buffer = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.limit() >= 20 && buffer.getInt(0) == 0x46546c67) { "Expected GLB" }
        require(buffer.getInt(4) == 2) { "Expected glTF 2" }
        var offset = 12
        var document: JSONObject? = null
        while (offset <= buffer.limit() - 8) {
            val length = buffer.getInt(offset)
            require(length >= 0 && length <= buffer.limit() - offset - 8) { "Invalid GLB chunk" }
            if (buffer.getInt(offset + 4) == 0x4e4f534a) {
                val bytes = ByteArray(length)
                buffer.position(offset + 8)
                buffer.get(bytes)
                document = JSONObject(String(bytes, Charsets.UTF_8))
                break
            }
            offset += 8 + length
        }
        val root = requireNotNull(document) { "GLB JSON missing" }
        val materialRows = root.optJSONArray("materials") ?: JSONArray()
        val materials = (0 until materialRows.length()).map { index ->
            val factor = materialRows.getJSONObject(index).optJSONObject("pbrMetallicRoughness")
                ?.optJSONArray("baseColorFactor")
            FloatArray(4) { channel -> factor?.optDouble(channel, 1.0)?.toFloat() ?: 1f }
        }
        val meshes = root.getJSONArray("meshes")
        val nodes = root.getJSONArray("nodes")
        return buildMap {
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                val meshIndex = node.optInt("mesh", -1)
                val name = node.optString("name")
                if (meshIndex < 0 || name.isBlank()) continue
                val primitives = meshes.getJSONObject(meshIndex).getJSONArray("primitives")
                put(name, (0 until primitives.length()).map { primitive ->
                    materials.getOrNull(primitives.getJSONObject(primitive).optInt("material", -1))
                        ?: floatArrayOf(1f, 1f, 1f, 1f)
                })
            }
        }
    }
}
