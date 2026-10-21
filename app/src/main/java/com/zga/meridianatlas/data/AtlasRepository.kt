package com.zga.meridianatlas.data

import android.content.Context
import androidx.room.*
import com.zga.meridianatlas.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

@Entity(tableName = "atlas_entries", primaryKeys = ["kind", "id"])
data class AtlasEntry(@ColumnInfo val kind: String, @ColumnInfo val id: String,
    val ordinal: Int, val body: String, val searchText: String)
@Entity(tableName = "content_meta")
data class ContentMeta(@PrimaryKey val id: Int = 1, val sha256: String)
@Dao
interface AtlasDao {
    @Query("SELECT * FROM atlas_entries WHERE kind = :kind ORDER BY ordinal")
    suspend fun entries(kind: String): List<AtlasEntry>
    @Query("SELECT * FROM atlas_entries WHERE searchText LIKE '%' || :query || '%' ORDER BY ordinal LIMIT 200")
    suspend fun search(query: String): List<AtlasEntry>
    @Query("SELECT sha256 FROM content_meta WHERE id = 1") suspend fun hash(): String?
    @Query("DELETE FROM atlas_entries") suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(rows: List<AtlasEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun meta(meta: ContentMeta)
}
@Database(entities = [AtlasEntry::class, ContentMeta::class], version = 1, exportSchema = false)
abstract class AtlasDatabase : RoomDatabase() { abstract fun atlas(): AtlasDao }

class AtlasRepository(private val context: Context) {
    private val database = Room.databaseBuilder(context, AtlasDatabase::class.java, "atlas.db").build()

    suspend fun load(): AtlasData = withContext(Dispatchers.IO) {
        val anatomy = context.assets.open("anatomy.json").bufferedReader().use { it.readText() }
        val points = context.assets.open("content/acupoints.json").bufferedReader().use { it.readText() }
        val routes = context.assets.open("content/meridians.json").bufferedReader().use { it.readText() }
        val fascia = context.assets.open("content/fascia.json").bufferedReader().use { it.readText() }
        // TARA remains a separate, unmodified third-party collection. Its text is read
        // on load and attached only in memory; it is never merged into the Room seed.
        val tara = TaraContent.fromTurtle(
            context.assets.open("third_party/tara/acupoints-1.7.2.ttl").bufferedReader().use { it.readText() })
        val hash = MessageDigest.getInstance("SHA-256").digest((anatomy + points + routes + fascia).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dao = database.atlas()
        if (dao.hash() != hash) {
            val rows = buildList {
                fun append(kind: String, array: JSONArray) {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val text = listOf(item.optString("nameZh"), item.optString("nameEn"),
                            item.optString("pinyin"), item.optString("pinyinSearch"), item.optJSONArray("codes"), item.optJSONArray("aliases"))
                            .joinToString(" ").lowercase()
                        add(AtlasEntry(kind, item.getString("id"), index, item.toString(), text))
                    }
                }
                append("structure", JSONObject(anatomy).getJSONArray("structures"))
                append("point", JSONObject(points).getJSONArray("points"))
                val routeRoot = JSONObject(routes)
                append("meridian", routeRoot.optJSONArray("meridians") ?: routeRoot.getJSONArray("routes"))
                append("fascial", JSONObject(fascia).getJSONArray("connections"))
            }
            database.withTransaction { dao.clear(); dao.insert(rows); dao.meta(ContentMeta(sha256 = hash)) }
        }
        AtlasData(dao.entries("structure").map { decodeStructure(JSONObject(it.body)) },
            dao.entries("point").map { row ->
                val point = decodePoint(JSONObject(row.body))
                point.copy(taraReferences = tara.lookup(point))
            },
            dao.entries("meridian").map { decodeMeridian(JSONObject(it.body)) },
            fascialConnections = dao.entries("fascial").map { decodeFascialConnection(JSONObject(it.body)) })
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it != "null" }
internal fun JSONArray?.vec(): Vec3? = if (this == null || length() < 3) null else
    Vec3(optDouble(0).toFloat(), optDouble(1).toFloat(), optDouble(2).toFloat())
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun decodeStructure(item: JSONObject): AnatomyStructure {
    val bounds = item.optJSONObject("bounds")
    val min = bounds?.optJSONArray("min").vec()
    val max = bounds?.optJSONArray("max").vec()
    val center = item.optJSONArray("center").vec() ?: if (min != null && max != null)
        Vec3((min.x+max.x)/2, (min.y+max.y)/2, (min.z+max.z)/2) else Vec3(0f, .9f, 0f)
    return AnatomyStructure(item.getString("id"), item.optString("nameZh", item.optString("nameEn")),
        item.optString("nameEn"), item.getString("system"), item.optString("region", "whole"),
        item.optInt("layer"), item.optString("nodeName", item.getString("id")), center,
        item.optString("descriptionZh"), item.optString("originZh"), item.optString("insertionZh"), item.optString("functionZh"),
        item.optString("tissueType"), item.optString("tissueTypeZh"), item.optString("sourceConceptNoteZh"),
        sourceConceptId = item.optString("sourceConceptId").takeUnless { it == "null" }.orEmpty(),
        sourceDataset = item.optString("sourceDataset").takeUnless { it == "null" }.orEmpty(),
        boundsMin = min, boundsMax = max)
}
internal fun decodePoint(item: JSONObject): Acupoint = Acupoint(
    item.getString("id"), item.optString("kind", "meridian"), item.getString("nameZh"),
    item.optString("pinyin"), item.optJSONArray("aliases").strings(), item.optJSONArray("codes").strings(),
    item.optString("meridianId").takeIf { it.isNotBlank() && it != "null" }, item.optString("region"),
    item.optString("locationZh"), item.optString("anatomyZh"), item.optJSONArray("sourceRefs").objects().map {
        val standard = when(it.optString("sourceId")) { "gb12346-2021" -> "GB/T 12346—2021"; "gb40997-2021" -> "GB/T 40997—2021"; else -> it.optString("sourceId") }
        "$standard · ${it.optString("clause")}" },
    item.optJSONArray("instances").objects().mapIndexed { index, instance ->
        val anchor = instance.optJSONObject("anchor") ?: instance
        PointInstance(instance.optString("id", "${item.getString("id")}:$index"),
            instance.optString("side", "midline"), instance.optInt("ordinal", index + 1),
            anchor.optJSONArray("position").vec(), anchor.optJSONArray("normal").vec(),
            anchor.optString("viewId", instance.optString("viewId", item.optString("viewPreset", "body"))),
            anchor.optString("status", "unmapped"), anchor.optJSONArray("landmarkRefs").strings(),
            anchor.optString("sourceSurface").takeIf { it.isNotBlank() },
            instance.optString("labelZh").takeIf { it.isNotBlank() }
                ?: instance.optString("level").takeIf { it.isNotBlank() }?.let { level ->
                    "${if (instance.optString("side") == "left") "左侧" else "右侧"} $level" })
    }, item.optString("reviewStatus", item.optString("coordinateReviewStatus", "unreviewed")),
    traditionalIndicationsZh = item.optString("traditionalIndicationsZh"),
    functionSourceRefs = item.optJSONArray("functionSourceRefs").strings(),
    functionReviewStatus = item.optString("functionReviewStatus", "pending-source-verification"))
internal fun decodeMeridian(item: JSONObject): Meridian {
    val colorText = item.optString("color", "#58D8C5")
    val color = try { android.graphics.Color.parseColor(colorText).toLong() and 0xffffffffL } catch (_: Exception) { 0xff58d8c5L }
    return Meridian(item.getString("id"), item.getString("nameZh"), item.optString("shortName", item.getString("nameZh")),
        item.optString("routeType", "primary"), color, item.optString("descriptionZh"),
        (item.optJSONArray("pointIds").strings() + item.optJSONArray("associatedPointIds").strings()).distinct(), item.optJSONArray("paths").objects().mapIndexed { index, path ->
            val points = path.optJSONArray("points")
            RoutePath(path.optString("id", "${item.getString("id")}:$index"), path.optString("side"),
                path.optBoolean("internal"), if (points == null) emptyList() else
                    (0 until points.length()).mapNotNull { points.optJSONArray(it).vec() },
                path.optString("reviewStatus", "provisional"))
        }, item.optJSONArray("sourceRefs").strings())
}
