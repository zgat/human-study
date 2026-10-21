package com.zga.meridianatlas

import com.zga.meridianatlas.data.TaraContent
import com.zga.meridianatlas.model.Acupoint
import com.zga.meridianatlas.model.TaraReference
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TaraContentTest {
    private fun point(id: String, codes: List<String>) = Acupoint(id, "", "", "", emptyList(),
        codes, null, "", "", "", emptyList(), emptyList(), "")
    private val prefixes = listOf(
        "@prefix TARA: <http://purl.org/tara/ontology/TARA_> .",
        "@prefix tara-ap: <http://purl.org/tara/ontology/annotation-property/> .",
        "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
        "@prefix dcterms: <http://purl.org/dc/terms/> .",
        "@prefix owl: <http://www.w3.org/2002/07/owl#> .").joinToString("\n", postfix = "\n")
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private fun shipped() = TaraContent.fromTurtle(File(assets, TaraContent.ASSET_PATH).readText())

    @Test fun turtlePreservesIndividualLiteralsEscapesMultilinePunctuationAndPhysicalSourceLines() {
        val location = "  first; [nested] .\r\nline \"four\" \\ tail  "
        val text = prefixes + "TARA:1 a owl:Class ;\n" +
            " rdfs:label \"LU 1\" ;\n tara-ap:hasLocationalDescription \"\"\"" +
            location.replace("\\", "\\\\") + "\"\"\" ;\n" +
            " tara-ap:hasPinyinLabel \"Zhongfu\\u0020raw\" ;\n" +
            " tara-ap:hasChineseLabel \"\\u4E2D\\u5E9C\" ;\n" +
            " tara-ap:hasListedIndications \"cough\", \"asthma\" ;\n" +
            " dcterms:bibliographicCitation \"Ref; . [one]\", \"Ref \\\"two\\\"\" .\n\n" +
            "TARA:2 a owl:Class ; rdfs:label \"LU 2\" ; tara-ap:hasLocationalDescription \"second\" .\n"
        val content = TaraContent.fromTurtle(text)
        val first = content.lookup("lu1", listOf("LU1")).single()
        assertEquals(listOf(location), first.fields["Location"])
        assertEquals(listOf("Zhongfu raw"), first.fields["Pinyin Label"])
        assertEquals(listOf("中府"), first.fields["Chinese Label"])
        assertEquals(listOf("cough", "asthma"), first.fields["Indications"])
        assertEquals(listOf("Ref; . [one]", "Ref \"two\""), first.fields["Reference"])
        assertEquals(6, first.rowNumber)
        assertEquals("http://purl.org/tara/ontology/TARA_1", first.sourceId)
        assertEquals("acupoints-1.7.2.ttl", first.sourceFile)
        assertEquals(text.take(text.indexOf("TARA:2 a")).count { it == '\n' } + 1,
            content.lookup("lu2").single().rowNumber)
    }

    @Test fun nonTargetSubjectsNestedRestrictionsAndMethodOrCuratorLiteralsNeverBecomeDisplayFields() {
        val text = prefixes + "# TARA:9 a owl:Class ; rdfs:label \"LU 9\" .\n" +
            "UBERON:1 a owl:Class ; rdfs:label \"LU 2\" ; tara-ap:hasLocationalDescription \"foreign\" .\n" +
            "TARA:3 a owl:AnnotationProperty ; rdfs:label \"LU 3\" ; tara-ap:hasLocationalDescription \"property\" .\n" +
            "TARA:1 a owl:Class ; rdfs:label \"LU 1\" ;\n" +
            " tara-ap:hasLocationalDescription \"top-level location\" ;\n" +
            " tara-ap:hasMethodDescription \"PROCEDURE_CANARY; . [ ] TARA:2\" ;\n" +
            " rdfs:comment \"CURATOR_CANARY\" ;\n" +
            " rdfs:subClassOf [ tara-ap:hasLocationalDescription \"NESTED_CANARY\" ;\n" +
            " rdfs:label \"LU 8\" ] .\n"
        val content = TaraContent.fromTurtle(text)
        val reference = content.lookup("lu1").single()
        assertEquals(setOf("Label", "Location"), reference.fields.keys)
        assertFalse(reference.fields.values.flatten().any { it.contains("CANARY") })
        for (code in listOf("lu2", "lu3", "lu8", "lu9")) assertTrue(content.lookup(code).isEmpty())
    }

    @Test fun shippedCollisionNamesStayDistinctAndYintangUsesExplicitCurrentCodeMapping() {
        val content = shipped()
        assertEquals("LI 2", content.lookup(point("li2", listOf("LI2"))).single().label)
        assertEquals("Erjian", content.lookup("ex-hn6", listOf("EX-HN6")).single().label)
        assertEquals("CV 19", content.lookup("cv19", listOf("CV19")).single().label)
        assertEquals("Zigongxue", content.lookup("ex-ca1", listOf("EX-CA1")).single().label)
        val yintang = content.lookup("gv24-plus", listOf("GV24+")).single()
        assertEquals("Yintang", yintang.label)
        assertEquals(listOf("EX-HN 3", "M-HN 3", "Yingtang"), yintang.fields["Synonym"])
        assertEquals("http://purl.org/tara/ontology/TARA_3135569", yintang.sourceId)
        assertEquals(770, yintang.rowNumber)
        assertTrue(content.lookup("Erjian").isEmpty())
        assertTrue(content.lookup("Zigong").isEmpty())
        assertTrue(content.lookup("du1", listOf("DU1")).isEmpty())
        assertTrue(content.lookup("cv1-male", listOf("CV1 (Male)")).isEmpty())
        assertEquals("CV 1", content.lookup("cv1").single().label)
    }

    @Test fun combinedJinjinYuyeKeepsTwoOriginalSubjectsAndGroupPointsDoNotExpandOrCrossMap() {
        val content = shipped()
        val pair = content.lookup("ex-hn12-hn13", listOf("EX-HN12", "EX-HN13"))
        assertEquals(listOf("Jinjing", "Yuye"), pair.map { it.label })
        assertEquals(listOf(834, 1233), pair.map { it.rowNumber })
        assertEquals(2, pair.map { it.sourceId }.toSet().size)
        assertEquals(listOf("EX-HN 12", "Jinjin", "M-HN 20a"), pair.first().fields["Synonym"])
        assertEquals(listOf("EX-HN 13", "M-HN 20b"), pair.last().fields["Synonym"])
        assertEquals("Huatuojiaji", content.lookup("ex-b2", listOf("EX-B2")).single().label)
        assertEquals("Sishencong", content.lookup("ex-hn1", listOf("EX-HN1")).single().label)
        assertEquals("Medial Xiyan", content.lookup("ex-le4", listOf("EX-LE4")).single().label)
        assertEquals("ST 35", content.lookup("st35", listOf("ST35")).single().label)
        assertEquals("Bitong", content.lookup("ex-hn8", listOf("EX-HN8")).single().label)
        assertEquals("Baichongwo", content.lookup("ex-le3", listOf("EX-LE3")).single().label)
    }

    @Test fun licensedReleaseMatches395CatalogueEntriesAndPreservesMissingFieldsWithoutCsvFallback() {
        val content = shipped()
        val pattern = Regex(""""id"\s*:\s*"([^"]+)"\s*,\s*"kind"\s*:\s*"(?:meridian|extra)".*?"codes"\s*:\s*\[([^]]*)]""",
            RegexOption.DOT_MATCHES_ALL)
        val catalogue = pattern.findAll(File(assets, "content/acupoints.json").readText()).map { match ->
            match.groupValues[1] to Regex("\"([^\"]+)\"").findAll(match.groupValues[2])
                .map { it.groupValues[1] }.toList()
        }.toList()
        assertEquals(413, catalogue.size)
        val lookedUp = catalogue.associate { (id, codes) -> id to content.lookup(id, codes) }
        assertEquals(395, lookedUp.count { it.value.isNotEmpty() })
        assertEquals(setOf("ex-hn2", "ex-hn9", "ex-hn10", "ex-hn11", "extra-xinshe",
            "extra-xueyadian", "extra-tituo", "extra-jieji", "ex-b6", "ex-ue5", "ex-ue6",
            "ex-ue8", "ex-le1", "ex-le8", "ex-le9", "extra-lineiting", "ex-le11", "ex-le12"),
            lookedUp.filterValues { it.isEmpty() }.keys)
        val records = lookedUp.values.flatten().distinctBy { it.sourceId }
        assertEquals(396, records.size)
        assertEquals(361, records.count { it.label.matches(Regex("[A-Z]{2} [0-9]+")) })
        assertEquals(396, records.count { it.fields.containsKey("Location") })
        assertEquals(383, records.count { it.fields.containsKey("Indications") })
        assertEquals(337, records.count { it.fields.containsKey("Vasculature") })
        assertEquals(326, records.count { it.fields.containsKey("Innervation") })
        assertTrue(records.all { it.sourceFile == "acupoints-1.7.2.ttl" })
        assertTrue(records.all { record -> record.fields.keys.all { it in TaraReference.DISPLAY_FIELDS } })
        assertTrue(records.none { "Acupuncture Method" in it.fields || "WHO Location" in it.fields })
        assertFalse(content.lookup("ex-hn6").single().fields.containsKey("Innervation"))
        assertFalse(content.lookup("ex-hn6").single().fields.containsKey("Chinese Label"))
        val lung = content.lookup("lu1").single()
        assertEquals(listOf("asthma", "cough", "fullness in the chest", "pain in the back",
            "pain in the chest", "pain in the shoulder"), lung.fields["Indications"])
        assertEquals(16070, lung.rowNumber)
        assertEquals(2, lung.fields.getValue("Reference").size)
    }

    @Test fun malformedOrWrongNamespaceInputFailsInsteadOfSilentlyAssigningUntrustedText() {
        for (suffix in listOf(
            "TARA:1 a owl:Class ; rdfs:label \"LU 1\" ; tara-ap:hasLocationalDescription \"unclosed",
            "TARA:1 a owl:Class ; rdfs:label \"LU 1\" ; tara-ap:hasLocationalDescription [ \"x\" .",
            "TARA:1 a owl:Class ; rdfs:label \"LU 1\" ; tara-ap:hasLocationalDescription \"x\" .\n" +
                "TARA:2 a owl:Class ; rdfs:label \"LU 1\" ; tara-ap:hasLocationalDescription \"y\" .")) {
            try { TaraContent.fromTurtle(prefixes + suffix); fail("Malformed/ambiguous source must be rejected") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
        try {
            TaraContent.fromTurtle(prefixes.replace("http://purl.org/tara/ontology/TARA_", "https://wrong.example/"))
            fail("The pinned vocabulary must not be impersonated")
        } catch (_: IllegalArgumentException) { /* expected */ }
        try {
            TaraReference("LU 1", "acupoints-1.7.2.ttl", 2, mapOf("Acupuncture Method" to listOf("hidden")))
            fail("Procedure fields must not enter the display model")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
