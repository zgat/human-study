package com.zga.meridianatlas.data

import com.zga.meridianatlas.model.Acupoint
import com.zga.meridianatlas.model.TaraReference
import java.util.Locale

/** Reads the explicitly licensed release Turtle independently of the Chinese catalogue and Room. */
class TaraContent private constructor(
    private val mainByCode: Map<String, TaraReference>,
    private val extraByLabel: Map<String, TaraReference>,
    private val extraByCode: Map<String, List<TaraReference>>
) {
    fun lookup(point: Acupoint): List<TaraReference> = lookup(point.id, point.codes)

    /** No pinyin/name search: Erjian and Zigong refer to different main and extra points. */
    fun lookup(pointId: String, codes: List<String> = emptyList()): List<TaraReference> {
        // The current national standard moved Yintang from EX-HN3 to GV24+.
        if (pointId == "gv24-plus" || codes.any { it.trim().uppercase(Locale.ROOT) == "GV24+" }) {
            return listOfNotNull(extraByLabel["Yintang"])
        }
        // IDs pin reviewed name mappings and preserve combined/group entries as source records.
        EXTRA_LABELS_BY_POINT_ID[pointId]?.let { labels ->
            return labels.mapNotNull(extraByLabel::get)
        }
        val mainCodes = codes.mapNotNull(::mainCode).ifEmpty { listOfNotNull(mainCode(pointId)) }
        if (mainCodes.isNotEmpty()) return mainCodes.distinct().mapNotNull(mainByCode::get)
        return codes.mapNotNull(::extraCode).distinct()
            .flatMap { extraByCode[it].orEmpty() }.distinctBy { it.sourceFile to it.rowNumber }
    }

    companion object {
        const val ASSET_PATH = "third_party/tara/acupoints-1.7.2.ttl"
        private const val SOURCE_FILE = "acupoints-1.7.2.ttl"
        private const val TARA_NAMESPACE = "http://purl.org/tara/ontology/TARA_"
        private val MAIN_LIMITS = mapOf("LU" to 11, "LI" to 20, "ST" to 45, "SP" to 21,
            "HT" to 9, "SI" to 19, "BL" to 67, "KI" to 27, "PC" to 9, "TE" to 23,
            "GB" to 44, "LR" to 14, "GV" to 28, "CV" to 24)
        private val MAIN_CODE = Regex("^(LU|LI|ST|SP|HT|SI|BL|KI|PC|TE|GB|LR|GV|CV)\\s*([1-9][0-9]*)$")
        private val EXTRA_CODE = Regex("^(EX-(?:HN|CA|B|UE|LE))\\s*([1-9][0-9]*)$")

        // Reviewed against each source Location, not inferred from globally matching pinyin.
        // Jinjing is the source spelling. Its separate Yuye record must not be concatenated.
        private val EXTRA_LABELS_BY_POINT_ID = mapOf(
            "ex-hn1" to listOf("Sishencong"), "ex-hn4" to listOf("Yuyao"),
            "ex-hn5" to listOf("Taiyang"), "ex-hn6" to listOf("Erjian"),
            "ex-hn7" to listOf("Qiuhou"), "ex-hn8" to listOf("Bitong"),
            "ex-hn12-hn13" to listOf("Jinjing", "Yuye"), "ex-hn14" to listOf("Yiming"),
            "ex-hn15" to listOf("Bailao"), "extra-anmian" to listOf("Anmian"),
            "extra-qianzheng" to listOf("Qianzheng"), "extra-jianqian" to listOf("Jianqian"),
            "ex-ca1" to listOf("Zigongxue"), "ex-b1" to listOf("Dingchuan"),
            "ex-b2" to listOf("Huatuojiaji"), "ex-b3" to listOf("Weiwanxiashu"),
            "ex-b4" to listOf("Pigen"), "ex-b7" to listOf("Yaoyan"),
            "ex-b8" to listOf("Shiqizhui"), "ex-ue1" to listOf("Zhoujian"),
            "ex-ue2" to listOf("Erbai"), "ex-ue3" to listOf("Zhongquan"),
            "ex-ue4" to listOf("Zhongkui"), "ex-ue7" to listOf("Yaotongxue"),
            "ex-ue9" to listOf("Baxie"), "ex-ue10" to listOf("Sifeng"),
            "ex-ue11" to listOf("Shixuan"), "ex-le2" to listOf("Heding"),
            "ex-le3" to listOf("Baichongwo"), "ex-le4" to listOf("Medial Xiyan"),
            "ex-le6" to listOf("Dannangxue"), "ex-le7" to listOf("Lanweixue"),
            "ex-le10" to listOf("Bafeng")
        )

        fun fromTurtle(text: String): TaraContent {
            val mainIndex = linkedMapOf<String, TaraReference>()
            val extraIndex = linkedMapOf<String, TaraReference>()
            val codeIndex = linkedMapOf<String, MutableList<TaraReference>>()
            val extraLabels = EXTRA_LABELS_BY_POINT_ID.values.flatten().toSet() + "Yintang"
            ReleaseTurtleReader(text).records().forEach { record ->
                val code = mainCode(record.label)
                if (code != null) {
                    require(mainIndex.put(code, record) == null) { "Duplicate TARA canonical label: $code" }
                } else {
                    val codes = record.fields["Synonym"].orEmpty().mapNotNull(::extraCode).distinct()
                    if (record.label in extraLabels || codes.isNotEmpty()) {
                        require(extraIndex.put(record.label, record) == null) { "Duplicate TARA extra label: ${record.label}" }
                        codes.forEach { extraCode -> codeIndex.getOrPut(extraCode) { mutableListOf() }.add(record) }
                    }
                }
            }
            return TaraContent(mainIndex.toMap(), extraIndex.toMap(), codeIndex.mapValues { it.value.toList() })
        }

        private fun mainCode(value: String): String? {
            val match = MAIN_CODE.matchEntire(value.trim().uppercase(Locale.ROOT)) ?: return null
            val meridian = match.groupValues[1]
            val number = match.groupValues[2].toIntOrNull() ?: return null
            return if (number <= MAIN_LIMITS.getValue(meridian)) "$meridian$number" else null
        }

        private fun extraCode(value: String): String? {
            val match = EXTRA_CODE.matchEntire(value.trim().uppercase(Locale.ROOT)) ?: return null
            return match.groupValues[1] + match.groupValues[2]
        }

        private val FIELD_PREDICATES = mapOf(
            "rdfs:label" to "Label", "tara-ap:hasPinyinLabel" to "Pinyin Label",
            "tara-ap:hasChineseLabel" to "Chinese Label", "tara-ap:hasSynonym" to "Synonym",
            "tara-ap:hasLocationalDescription" to "Location", "tara-ap:hasListedIndications" to "Indications",
            "tara-ap:hasVasculatureInformation" to "Vasculature",
            "tara-ap:hasInnervationInformation" to "Innervation", "dcterms:bibliographicCitation" to "Reference"
        )

        /**
         * Reader for the pinned 1.7.2 serializer's named-class/literal layout, not a general RDF parser.
         * Nested restrictions, non-TARA subjects and non-allowlisted predicates never become fields.
         */
        private class ReleaseTurtleReader(private val text: String) {
            private data class Token(val value: String, val line: Int, val kind: Char = 'w', val language: String? = null)
            private var index = 0
            private var line = 1
            private val expectedPrefixes = mapOf("TARA:" to TARA_NAMESPACE,
                "tara-ap:" to "http://purl.org/tara/ontology/annotation-property/",
                "rdfs:" to "http://www.w3.org/2000/01/rdf-schema#",
                "dcterms:" to "http://purl.org/dc/terms/", "owl:" to "http://www.w3.org/2002/07/owl#")
            private val prefixes = mutableMapOf<String, String>()

            fun records(): List<TaraReference> {
                val result = mutableListOf<TaraReference>()
                val statement = mutableListOf<Token>()
                val brackets = mutableListOf<String>()
                while (true) {
                    val token = next() ?: break
                    if (token.kind == 'p') when (token.value) {
                        "[", "(" -> brackets.add(token.value)
                        "]", ")" -> {
                            val open = brackets.removeLastOrNull()
                            require(open == if (token.value == "]") "[" else "(") { "Unbalanced Turtle brackets at line ${token.line}" }
                        }
                    }
                    if (token.kind == 'p' && token.value == "." && brackets.isEmpty()) {
                        record(statement)?.let(result::add)
                        statement.clear()
                    } else statement.add(token)
                }
                require(statement.isEmpty() && brackets.isEmpty()) { "Incomplete Turtle statement" }
                return result
            }

            private fun record(tokens: List<Token>): TaraReference? {
                if (tokens.isEmpty()) return null
                if (tokens.first().value == "@prefix") {
                    require(tokens.size == 3 && tokens[2].kind == 'i') { "Unsupported Turtle prefix declaration" }
                    val name = tokens[1].value
                    expectedPrefixes[name]?.let { require(tokens[2].value == it) { "Unexpected Turtle namespace: $name" } }
                    prefixes[name] = tokens[2].value
                    return null
                }
                val subject = tokens.first()
                if (!Regex("TARA:[0-9]+").matches(subject.value) || tokens.size < 3 ||
                    tokens[1].value != "a" || tokens[2].value != "owl:Class") return null
                require(expectedPrefixes.all { prefixes[it.key] == it.value }) { "Missing pinned Turtle namespaces" }
                val fields = linkedMapOf<String, MutableList<String>>()
                var depth = 0
                var predicate: String? = null
                tokens.drop(1).forEach { token ->
                    if (token.kind == 'p' && token.value in listOf("[", "(")) { depth++; return@forEach }
                    if (token.kind == 'p' && token.value in listOf("]", ")")) { depth--; return@forEach }
                    if (depth != 0) return@forEach
                    if (token.kind == 'p' && token.value == ";") { predicate = null; return@forEach }
                    if (predicate == null) { predicate = token.value; return@forEach }
                    val field = FIELD_PREDICATES[predicate] ?: return@forEach
                    if (token.kind == 'l' && token.value.isNotBlank() &&
                        (token.language == null || token.language.startsWith("en") || field == "Chinese Label")) {
                        fields.getOrPut(field) { mutableListOf() }.add(token.value)
                    }
                }
                // A descriptive locational literal distinguishes point records from meridian/classes.
                if (fields["Location"].isNullOrEmpty()) return null
                val label = fields["Label"]?.singleOrNull() ?: return null
                return TaraReference(label, SOURCE_FILE, subject.line, fields.mapValues { it.value.toList() },
                    TARA_NAMESPACE + subject.value.substringAfter(':'))
            }

            private fun next(): Token? {
                while (index < text.length) {
                    if (text[index].isWhitespace() || text[index] == '\uFEFF') { advance(); continue }
                    if (text[index] == '#') { while (index < text.length && text[index] != '\n') advance(); continue }
                    break
                }
                if (index >= text.length) return null
                val startLine = line
                val char = text[index]
                if (char == '"' || char == '\'') return literal(char, startLine)
                if (char == '<') {
                    advance(); val start = index
                    while (index < text.length && text[index] != '>') advance()
                    require(index < text.length) { "Unclosed Turtle IRI" }
                    val iri = text.substring(start, index); advance()
                    require('\\' !in iri) { "Escaped IRIs unsupported by pinned release reader" }
                    return Token(iri, startLine, 'i')
                }
                if (char in ";,[]().") { advance(); return Token(char.toString(), startLine, 'p') }
                val start = index
                while (index < text.length && !text[index].isWhitespace() && text[index] !in ";,[]()<>\"'#") {
                    if (text[index] == '.' && (index + 1 == text.length || text[index + 1].isWhitespace())) break
                    advance()
                }
                require(index > start) { "Unsupported Turtle token at line $line" }
                return Token(text.substring(start, index), startLine)
            }

            private fun literal(quote: Char, startLine: Int): Token {
                val triple = text.startsWith(quote.toString().repeat(3), index)
                val delimiter = quote.toString().repeat(if (triple) 3 else 1)
                repeat(delimiter.length) { advance() }
                val value = StringBuilder()
                var closed = false
                while (index < text.length) {
                    if (text.startsWith(delimiter, index)) { repeat(delimiter.length) { advance() }; closed = true; break }
                    val char = text[index]
                    require(triple || char !in "\r\n") { "Newline in short Turtle literal at line $line" }
                    advance()
                    if (char != '\\') { value.append(char); continue }
                    require(index < text.length) { "Incomplete Turtle escape" }
                    val escape = text[index]; advance()
                    when (escape) {
                        't' -> value.append('\t'); 'b' -> value.append('\b'); 'n' -> value.append('\n')
                        'r' -> value.append('\r'); 'f' -> value.append('\u000C')
                        '"', '\'', '\\' -> value.append(escape)
                        'u', 'U' -> {
                            val digits = if (escape == 'u') 4 else 8
                            require(index + digits <= text.length) { "Incomplete Turtle Unicode escape" }
                            val code = text.substring(index, index + digits).toIntOrNull(16)
                            require(code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) { "Invalid Turtle Unicode escape" }
                            value.append(String(Character.toChars(code)))
                            repeat(digits) { advance() }
                        }
                        else -> error("Unsupported Turtle escape: $escape")
                    }
                }
                require(closed) { "Unclosed Turtle literal starting line $startLine" }
                var language: String? = null
                if (index < text.length && text[index] == '@') {
                    advance(); val start = index
                    while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '-')) advance()
                    require(index > start) { "Empty Turtle language tag" }
                    language = text.substring(start, index).lowercase(Locale.ROOT)
                }
                return Token(value.toString(), startLine, 'l', language)
            }

            private fun advance() { if (text[index] == '\n') line++; index++ }
        }
    }
}
