package dev.bsb.moakeyvim.view.suggestion

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 자동완성 바에서 자동 치환 단어에 쓸 강조색을 배경 대비가 확보되도록 보정한다.
 *
 * 스킨 강조색은 어두운 키보드 배경을 전제로 고른 값이라, 화이트 스킨(바 배경 #DDDDDD)에서는
 * #5E97EE 가 2.17:1 밖에 안 나와 그대로 쓰면 흐려서 읽기 어렵다.
 * 색조는 유지한 채 명도만 밀어서 WCAG AA(4.5:1) 를 맞춘다.
 *
 * android.graphics.Color 를 쓰지 않는다 - 유닛 테스트에서 Android 프레임워크가 스텁이라
 * colorToHSV 류가 동작하지 않기 때문이다.
 */
object SuggestionAccentColor {

    private const val MIN_CONTRAST = 4.5
    private const val STEP = 0.02
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun resolve(accent: Int, background: Int, minContrast: Double = MIN_CONTRAST): Int {
        if (contrastRatio(accent, background) >= minContrast) return accent

        val darken = contrastRatio(BLACK, background) >= contrastRatio(WHITE, background)
        var best = accent
        var bestRatio = contrastRatio(accent, background)
        var amount = STEP
        while (amount <= 1.0) {
            val candidate = if (darken) scaleToBlack(accent, amount) else blendToWhite(accent, amount)
            val ratio = contrastRatio(candidate, background)
            if (ratio >= minContrast) return candidate
            if (ratio > bestRatio) {
                best = candidate
                bestRatio = ratio
            }
            amount += STEP
        }
        return best
    }

    fun contrastRatio(foreground: Int, background: Int): Double {
        val fg = relativeLuminance(foreground)
        val bg = relativeLuminance(background)
        return (max(fg, bg) + 0.05) / (min(fg, bg) + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        val r = linearize((color shr 16) and 0xFF)
        val g = linearize((color shr 8) and 0xFF)
        val b = linearize(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    // 모든 채널을 같은 비율로 줄이므로 색조가 그대로 유지된다
    private fun scaleToBlack(color: Int, amount: Double): Int = pack(color) { channel ->
        (channel * (1.0 - amount)).toInt()
    }

    private fun blendToWhite(color: Int, amount: Double): Int = pack(color) { channel ->
        channel + ((255 - channel) * amount).toInt()
    }

    private fun pack(color: Int, transform: (Int) -> Int): Int {
        val a = (color shr 24) and 0xFF
        val r = transform((color shr 16) and 0xFF).coerceIn(0, 255)
        val g = transform((color shr 8) and 0xFF).coerceIn(0, 255)
        val b = transform(color and 0xFF).coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
