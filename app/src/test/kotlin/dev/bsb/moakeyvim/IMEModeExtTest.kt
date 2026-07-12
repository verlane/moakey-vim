package dev.bsb.moakeyvim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IMEModeExtTest {

    @Test
    fun `한국어 계열 모드는 isKoreanFamily true 반환`() {
        assertTrue(IMEMode.IME_KO.isKoreanFamily())
        assertTrue(IMEMode.IME_KO_ARROW.isKoreanFamily())
        assertTrue(IMEMode.IME_KO_PHONE.isKoreanFamily())
        assertTrue(IMEMode.IME_KO_PUNCTUATION.isKoreanFamily())
        assertTrue(IMEMode.IME_KO_NUMBER.isKoreanFamily())
        assertTrue(IMEMode.IME_EMOJI.isKoreanFamily())
    }

    @Test
    fun `영어 계열 모드는 isKoreanFamily false 반환`() {
        assertFalse(IMEMode.IME_EN.isKoreanFamily())
        assertFalse(IMEMode.IME_EN_ARROW.isKoreanFamily())
        assertFalse(IMEMode.IME_EN_PHONE.isKoreanFamily())
        assertFalse(IMEMode.IME_EN_PUNCTUATION.isKoreanFamily())
        assertFalse(IMEMode.IME_EN_NUMBER.isKoreanFamily())
    }
}
