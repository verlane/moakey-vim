package dev.bsb.moakeyvim

fun IMEMode.isKoreanFamily(): Boolean = when (this) {
    IMEMode.IME_KO,
    IMEMode.IME_KO_ARROW,
    IMEMode.IME_KO_PHONE,
    IMEMode.IME_KO_PUNCTUATION,
    IMEMode.IME_KO_NUMBER,
    IMEMode.IME_EMOJI -> true
    IMEMode.IME_EN,
    IMEMode.IME_EN_ARROW,
    IMEMode.IME_EN_PHONE,
    IMEMode.IME_EN_PUNCTUATION,
    IMEMode.IME_EN_NUMBER -> false
}
