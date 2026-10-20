package com.zga.meridianatlas

import com.zga.meridianatlas.model.SearchIndex
import org.junit.Assert.*
import org.junit.Test

class SearchIndexTest {
    @Test fun pinyinCanBeTypedWithoutTonesOrSyllablePunctuation() {
        assertTrue(SearchIndex.matches("fuai", "Fù'āi"))
        assertTrue(SearchIndex.matches("zhongfu", "Zhōngfǔ"))
    }
    @Test fun standardCodesAcceptCommonKeyboardForms() {
        assertTrue(SearchIndex.matches("exhn12", "EX-HN12"))
        assertTrue(SearchIndex.matches("ｌｕ１", "LU1"))
        assertTrue(SearchIndex.matches("GV24+", "GV24+"))
    }
    @Test fun namesAndAliasesAreIndependentSearchCandidates() {
        assertTrue(SearchIndex.matches("合谷", "合谷", "LI4"))
        assertFalse(SearchIndex.matches("unknown", "合谷", "LI4"))
    }
}
