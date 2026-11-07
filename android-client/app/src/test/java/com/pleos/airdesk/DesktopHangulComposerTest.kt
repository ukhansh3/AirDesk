package com.pleos.airdesk

import org.junit.Assert.*
import org.junit.Test

class DesktopHangulComposerTest {
    private fun typeKeys(keys: String): String {
        val composer = DesktopHangulComposer()
        val committed = StringBuilder()
        keys.forEach { key ->
            val jamo = DesktopHangulComposer.fromKey("Key${key.uppercaseChar()}", key.isUpperCase())!!
            val step = composer.input(jamo)
            committed.append(step.committed)
        }
        return committed.toString() + composer.active
    }
    @Test fun ordinaryWordsComposeAcrossSyllables() {
        assertEquals("한글", typeKeys("gksrmf"))
        assertEquals("안녕하세요", typeKeys("dkssudgktpdy"))
        assertEquals("컴퓨터", typeKeys("zjavbxj"))
    }
    @Test fun shiftedInitialsAndVowelsArePcTwoSetKeys() {
        assertEquals("까", typeKeys("Rk"))
        assertEquals("또", typeKeys("Eh"))
        assertEquals("쌔", typeKeys("To"))
        assertEquals("얘", typeKeys("dO"))
        assertEquals("예", typeKeys("dP"))
    }
    @Test fun compoundMedialsJoinAndBackspaceDecomposesOneStep() {
        val composer = DesktopHangulComposer()
        "ㄱㅗㅏ".forEach(composer::input)
        assertEquals("과", composer.active)
        assertEquals("고", composer.backspace())
        assertEquals("ㄱ", composer.backspace())
        assertEquals("", composer.backspace())
        assertEquals("궤", typeKeys("rnp"))
        assertEquals("의", typeKeys("dml"))
    }
    @Test fun compoundFinalSplitsBeforeFollowingVowel() {
        val composer = DesktopHangulComposer()
        "ㄷㅏㄹㄱ".forEach(composer::input)
        assertEquals("닭", composer.active)
        val step = composer.input('ㅏ')
        assertEquals("달", step.committed)
        assertEquals("가", step.composing)
    }
    @Test fun simpleFinalMigratesAndCompoundFinalBackspaceIsComponentWise() {
        val composer = DesktopHangulComposer()
        "ㄱㅏㄴ".forEach(composer::input)
        assertEquals(DesktopHangulComposer.Step("가", "나"), composer.input('ㅏ'))
        composer.reset()
        "ㄱㅏㅂㅅ".forEach(composer::input)
        assertEquals("값", composer.active)
        assertEquals("갑", composer.backspace())
        assertEquals("가", composer.backspace())
    }
    @Test fun standaloneJamoAndRejectedNonHangulDoNotInventSyllables() {
        val composer = DesktopHangulComposer()
        assertEquals("ㅏ", composer.input('ㅏ').composing)
        assertEquals(DesktopHangulComposer.Step("ㅏ", "ㄱ"), composer.input('ㄱ'))
        assertEquals(DesktopHangulComposer.Step("ㄱ", "ㄱ"), composer.input('ㄱ'))
        try { composer.input('A'); fail("Non-jamo accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun everySupportedCompoundFinalHasKnownKoreanSyllableOutput() {
        val examples=mapOf("ㄴㅓㄱㅅ" to "넋", "ㅇㅏㄴㅈ" to "앉", "ㅇㅏㄴㅎ" to "않", "ㅇㅣㄹㄱ" to "읽",
            "ㅅㅏㄹㅁ" to "삶", "ㄴㅓㄹㅂ" to "넓", "ㄱㅗㄹㅅ" to "곬", "ㅎㅏㄹㅌ" to "핥", "ㅇㅡㄹㅍ" to "읊", "ㅅㅣㄹㅎ" to "싫", "ㄱㅏㅂㅅ" to "값")
        examples.forEach { (input,expected) -> val c=DesktopHangulComposer();input.forEach(c::input);assertEquals(expected,c.active) }
        assertEquals("밖",typeKeys("qkR"))
        assertEquals("있어",typeKeys("dlTdj"))
    }
}
