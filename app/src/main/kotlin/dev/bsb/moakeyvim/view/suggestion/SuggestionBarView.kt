package dev.bsb.moakeyvim.view.suggestion

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.util.TextWidthClassifier
import dev.bsb.moakeyvim.util.stripUrlPrefix
import dev.bsb.moakeyvim.view.skin.SkinApplier

class SuggestionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onPick: ((String, Boolean) -> Unit)? = null
    var onWordLongClick: ((String) -> Unit)? = null
    var onCursorLeft: (() -> Unit)? = null
    var onCursorRight: (() -> Unit)? = null
    var onUndoRedo: ((isUndo: Boolean) -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onOpenClipboardPanel: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private var currentTextColor: Int
    private var currentBgColor: Int
    private var currentKeyBgColor: Int
    private var currentAccentColor: Int

    init {
        val skin = SettingsPreferences.getKeyboardSkin(context)
        currentTextColor = SkinApplier.fgColor(context, skin)
        currentBgColor = SkinApplier.keyboardBgColor(context, skin)
        currentKeyBgColor = SkinApplier.keyBgColor(context, skin)
        currentAccentColor = resolveAccentColor(skin, currentBgColor)
        setBackgroundColor(currentBgColor)
    }
    private var isUndoMode = true

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // 반드시 init 블록보다 앞에 선언해야 buildIconButton 호출 시 올바른 값이 사용됨
    private val hPad = (PADDING_H_DP * density).toInt()
    private val vPad = (PADDING_V_DP * density).toInt()
    private val iconPadH = (ICON_PADDING_H_DP * density).toInt()
    private val iconPadV = (ICON_PADDING_V_DP * density).toInt()
    private val iconBtnW = (ICON_SIZE_DP * density).toInt() + 2 * iconPadH

    private val container = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val scrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
    }
    private val btnCursorLeft: ImageButton
    private val btnCursorRight: ImageButton
    private val btnUndoRedo: ImageButton
    private val btnSettings: ImageButton
    private val leftActions: LinearLayout
    private val rightActions: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        btnCursorLeft = buildIconButton(R.drawable.ic_arrow_left, repeat = true) { onCursorLeft?.invoke() }
        btnCursorRight = buildIconButton(R.drawable.ic_arrow_right, repeat = true) { onCursorRight?.invoke() }
        btnUndoRedo = buildIconButton(R.drawable.ic_undo) {
            onUndoRedo?.invoke(isUndoMode)
            isUndoMode = !isUndoMode
            btnUndoRedo.setImageResource(if (isUndoMode) R.drawable.ic_undo else R.drawable.ic_redo)
        }
        btnSettings = buildIconButton(R.drawable.ic_settings) { onSettings?.invoke() }

        leftActions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnCursorLeft, LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(btnCursorRight, LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        rightActions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnUndoRedo, LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(btnSettings, LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
        }

        scrollView.addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(leftActions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scrollView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(rightActions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun resetUndoRedo() {
        if (!isUndoMode) {
            isUndoMode = true
            btnUndoRedo.setImageResource(R.drawable.ic_undo)
        }
    }

    fun setSuggestions(words: List<String>, hotstringExpansions: Set<String> = emptySet()) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        scrollView.scrollTo(0, 0)
        val showFunctionKeys = words.isEmpty()
        leftActions.visibility = if (showFunctionKeys) VISIBLE else GONE
        rightActions.visibility = if (showFunctionKeys) VISIBLE else GONE
        if (words.isNotEmpty()) {
            val skin = SettingsPreferences.getKeyboardSkin(context)
            val wordColor = dimTextColor(SkinApplier.fgColor(context, skin))
            currentAccentColor = resolveAccentColor(skin, SkinApplier.keyboardBgColor(context, skin))
            words.forEach { word ->
                container.addView(
                    buildWordView(word, word in hotstringExpansions, wordColor, currentAccentColor)
                )
            }
        }
    }

    fun showClipboard(text: String, onPaste: (String) -> Unit) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER
        scrollView.scrollTo(0, 0)
        leftActions.visibility = VISIBLE
        rightActions.visibility = VISIBLE
        val isOneHand = SettingsPreferences.getOneHandMode(context).isReduced
        val displayText = stripUrlPrefix(text)
        // displayText 기준으로 판정 — 표시 길이는 URL 프리픽스 제거 후 텍스트 기준이어야 함
        val isWide = TextWidthClassifier.isWideGlyphText(displayText)
        val maxLen = when {
            isWide && isOneHand -> MAX_CLIPBOARD_LEN_WIDE_ONE_HAND
            isWide -> MAX_CLIPBOARD_LEN_WIDE
            isOneHand -> MAX_CLIPBOARD_LEN_NARROW_ONE_HAND
            else -> MAX_CLIPBOARD_LEN_NARROW
        }
        val preview = if (displayText.length > maxLen) displayText.take(maxLen) + "…" else displayText
        container.addView(buildClipboardChip(preview, text, onPaste))
    }

    fun showClipboardIconOnly() {
        container.removeAllViews()
        container.gravity = Gravity.CENTER
        scrollView.scrollTo(0, 0)
        leftActions.visibility = VISIBLE
        rightActions.visibility = VISIBLE
        container.addView(buildClipboardIconButton(), LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    fun showEmpty(showClipboardIcon: Boolean = true) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER
        scrollView.scrollTo(0, 0)
        leftActions.visibility = VISIBLE
        rightActions.visibility = VISIBLE
        if (showClipboardIcon) {
            container.addView(buildClipboardIconButton(), LinearLayout.LayoutParams(iconBtnW, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun showSelectionActions(onCut: () -> Unit, onCopy: () -> Unit) {
        container.removeAllViews()
        container.gravity = Gravity.CENTER
        scrollView.scrollTo(0, 0)
        leftActions.visibility = VISIBLE
        rightActions.visibility = VISIBLE
        val skin = SettingsPreferences.getKeyboardSkin(context)
        val actionColor = dimTextColor(SkinApplier.fgColor(context, skin))
        container.addView(buildActionView(context.getString(R.string.key_cut), onCut, actionColor))
        container.addView(buildActionView(context.getString(R.string.key_copy), onCopy, actionColor))
    }

    fun applyColors(textColor: Int, bgColor: Int, keyBgColor: Int = Color.WHITE) {
        currentTextColor = dimTextColor(textColor)
        currentBgColor = bgColor
        currentKeyBgColor = keyBgColor
        currentAccentColor = resolveAccentColor(SettingsPreferences.getKeyboardSkin(context), bgColor)
        setBackgroundColor(bgColor)
        val tintList = ColorStateList.valueOf(textColor)
        btnCursorLeft.imageTintList = tintList
        btnCursorRight.imageTintList = tintList
        btnUndoRedo.imageTintList = tintList
        btnSettings.imageTintList = tintList
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            when {
                child is LinearLayout && child.tag == TAG_CLIPBOARD_CHIP -> {
                    (child.getChildAt(0) as? ImageView)?.imageTintList = tintList
                    (child.getChildAt(1) as? TextView)?.setTextColor(textColor)
                    child.background = buildChipBackground()
                }
                child is TextView && child.tag == TAG_HOTSTRING_WORD ->
                    child.setTextColor(currentAccentColor)
                child is TextView -> child.setTextColor(currentTextColor)
                child is ImageButton -> child.imageTintList = tintList
            }
        }
    }

    private fun buildWordView(
        word: String,
        isHotstring: Boolean,
        wordColor: Int,
        accentColor: Int,
    ): TextView {
        return TextView(context).apply {
            text = word.trim()
            textSize = TEXT_SIZE_SP
            setTextColor(if (isHotstring) accentColor else wordColor)
            if (isHotstring) tag = TAG_HOTSTRING_WORD
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = buildRipple()
            setOnClickListener { onPick?.invoke(word, isHotstring) }
            setOnLongClickListener {
                onWordLongClick?.invoke(word)
                true
            }
        }
    }

    // compound drawable 대신 ImageView + TextView 수평 레이아웃으로 정확한 수직 정렬 보장
    // 항상 최신 스킨 색상을 직접 읽어서 생성 타이밍과 무관하게 올바른 색상 적용
    private fun buildClipboardChip(displayText: String, fullText: String, onPaste: (String) -> Unit): LinearLayout {
        val skin = SettingsPreferences.getKeyboardSkin(context)
        val textColor = SkinApplier.fgColor(context, skin)
        val chipBgColor = SkinApplier.keyBgColor(context, skin)
        val iconSize = (16 * density).toInt()
        val margin = (4 * density).toInt()
        val chipH = (CHIP_PADDING_H_DP * density).toInt()
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val chipHeight = (CHIP_HEIGHT_DP * density).toInt()
            setPadding(chipH, 0, chipH, 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight)
                .apply {
                    setMargins(margin, 0, margin, 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
            tag = TAG_CLIPBOARD_CHIP
            background = buildChipBackground(chipBgColor, textColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { onPaste(fullText) }

            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_content_paste)
                imageTintList = ColorStateList.valueOf(textColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = margin
                }
            })
            addView(TextView(context).apply {
                text = displayText
                textSize = TEXT_SIZE_SP * 0.8f
                setTextColor(textColor)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            })
        }
    }

    private fun buildClipboardIconButton(): ImageButton {
        return buildIconButton(R.drawable.ic_content_paste) { onOpenClipboardPanel?.invoke() }
    }

    private fun buildActionView(label: String, action: () -> Unit, actionColor: Int): TextView {
        return TextView(context).apply {
            text = label
            textSize = TEXT_SIZE_SP
            setTextColor(actionColor)
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = buildRipple()
            setOnClickListener { action() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val skin = SettingsPreferences.getKeyboardSkin(context)
        applyColors(
            SkinApplier.fgColor(context, skin),
            SkinApplier.keyboardBgColor(context, skin),
            SkinApplier.keyBgColor(context, skin),
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildIconButton(iconRes: Int, repeat: Boolean = false, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(currentTextColor)
            setPadding(iconPadH, iconPadV, iconPadH, iconPadV)
            background = buildRipple()
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (repeat) {
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true
                            onClick()
                            repeatRunnable = object : Runnable {
                                override fun run() {
                                    onClick()
                                    repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
                                }
                            }.also { repeatHandler.postDelayed(it, REPEAT_DELAY_MS) }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                            repeatRunnable = null
                        }
                    }
                    true
                }
            } else {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun resolveAccentColor(skin: KeyboardSkin, bgColor: Int): Int =
        SuggestionAccentColor.resolve(SkinApplier.fgAccentColor(context, skin), bgColor)

    private fun dimTextColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= 0.88f
        return Color.HSVToColor(hsv)
    }

    private fun buildRipple(): RippleDrawable {
        return RippleDrawable(ColorStateList.valueOf(0x22000000), null, ColorDrawable(Color.WHITE))
    }

    private fun buildChipBackground(
        bgColor: Int = currentKeyBgColor,
        textColor: Int = currentTextColor,
    ): android.graphics.drawable.Drawable {
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CHIP_CORNER_DP * density
            setColor(bgColor)
        }
        val ripple = ColorStateList.valueOf(
            Color.argb(64, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
        )
        return RippleDrawable(ripple, fill, fill)
    }

    companion object {
        private const val TAG_CLIPBOARD_CHIP = "clipboard_chip"
        private const val TAG_HOTSTRING_WORD = "hotstring_word"
        private const val TEXT_SIZE_SP = 15.3f
        private const val PADDING_H_DP = 5
        private const val PADDING_V_DP = 4
        private const val CHIP_HEIGHT_DP = 24
        private const val CHIP_PADDING_H_DP = 6
        private const val CHIP_CORNER_DP = 12f
        private const val ICON_SIZE_DP = 20
        private const val ICON_PADDING_H_DP = 14
        private const val ICON_PADDING_V_DP = 4
        private const val MAX_CLIPBOARD_LEN_WIDE = 7
        private const val MAX_CLIPBOARD_LEN_NARROW = 12
        private const val MAX_CLIPBOARD_LEN_WIDE_ONE_HAND = 3
        private const val MAX_CLIPBOARD_LEN_NARROW_ONE_HAND = 6
        private const val REPEAT_DELAY_MS = 500L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
