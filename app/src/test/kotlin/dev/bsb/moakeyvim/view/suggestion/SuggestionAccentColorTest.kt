package dev.bsb.moakeyvim.view.suggestion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionAccentColorTest {

    // colors_skin.xml 의 (accent, keyboardBg) 쌍 — 자동완성 바 배경은 keyboardBg 다
    private val skinPairs = listOf(
        "화이트" to (0xFF5E97EE.toInt() to 0xFFDDDDDD.toInt()),
        "다크그레이" to (0xFF64D2FF.toInt() to 0xFF1C1C1E.toInt()),
        "블랙" to (0xFF64D2FF.toInt() to 0xFF000000.toInt()),
        "블루" to (0xFFFFD740.toInt() to 0xFF1A237E.toInt()),
        "그린" to (0xFFFFEB3B.toInt() to 0xFF1B5E20.toInt()),
    )

    @Test
    fun `contrast ratio of black on white is about 21`() {
        val ratio = SuggestionAccentColor.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, ratio, 0.1)
    }

    @Test
    fun `contrast ratio of identical colors is 1`() {
        val ratio = SuggestionAccentColor.contrastRatio(0xFF5E97EE.toInt(), 0xFF5E97EE.toInt())
        assertEquals(1.0, ratio, 0.001)
    }

    @Test
    fun `accent with enough contrast is returned unchanged`() {
        val accent = 0xFF64D2FF.toInt()
        assertEquals(accent, SuggestionAccentColor.resolve(accent, 0xFF1C1C1E.toInt()))
    }

    @Test
    fun `washed out accent on light background is darkened until readable`() {
        val accent = 0xFF5E97EE.toInt()
        val background = 0xFFDDDDDD.toInt()
        val resolved = SuggestionAccentColor.resolve(accent, background)

        assertTrue("원본은 대비가 부족해야 테스트가 의미 있다", SuggestionAccentColor.contrastRatio(accent, background) < 4.5)
        assertTrue("조정 후 4.5:1 이상이어야 한다", SuggestionAccentColor.contrastRatio(resolved, background) >= 4.5)
    }

    @Test
    fun `darkened accent keeps its hue`() {
        val resolved = SuggestionAccentColor.resolve(0xFF5E97EE.toInt(), 0xFFDDDDDD.toInt())
        val r = (resolved shr 16) and 0xFF
        val g = (resolved shr 8) and 0xFF
        val b = resolved and 0xFF
        assertTrue("파란색 계열이 유지되어야 한다 (b=$b, g=$g, r=$r)", b > g && g > r)
    }

    @Test
    fun `alpha channel is preserved`() {
        val resolved = SuggestionAccentColor.resolve(0xFF5E97EE.toInt(), 0xFFDDDDDD.toInt())
        assertEquals(0xFF, (resolved ushr 24) and 0xFF)
    }

    @Test
    fun `every skin pair reaches the readable threshold`() {
        skinPairs.forEach { (name, pair) ->
            val (accent, background) = pair
            val ratio = SuggestionAccentColor.contrastRatio(SuggestionAccentColor.resolve(accent, background), background)
            assertTrue("$name 스킨 명암비가 부족하다: $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `accent identical to background is pushed away from it`() {
        val background = 0xFFDDDDDD.toInt()
        val resolved = SuggestionAccentColor.resolve(background, background)
        assertTrue(SuggestionAccentColor.contrastRatio(resolved, background) >= 4.5)
    }

    @Test
    fun `accent on mid gray background picks the side with more contrast`() {
        val background = 0xFF808080.toInt()
        val resolved = SuggestionAccentColor.resolve(0xFF808080.toInt(), background)
        // 중간 회색에서는 어느 쪽도 4.5 에 못 미치므로, 더 나은 쪽(검정)으로 끝까지 밀어야 한다
        assertTrue(SuggestionAccentColor.contrastRatio(resolved, background) > 4.0)
    }
}
