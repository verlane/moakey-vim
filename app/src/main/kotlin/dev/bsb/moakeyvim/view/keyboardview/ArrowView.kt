package dev.bsb.moakeyvim.view.keyboardview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.databinding.ArrowViewBinding
import dev.bsb.moakeyvim.view.keytouchlistener.EnterKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.FunctionalKeyTouchListener
import dev.bsb.moakeyvim.view.message.SpecialKey
import dev.bsb.moakeyvim.view.keytouchlistener.RepeatKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SimpleKeyTouchListener
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.view.keytouchlistener.SpaceKeyTouchListener
import dev.bsb.moakeyvim.view.message.SpecialKeyMessage
import dev.bsb.moakeyvim.view.message.StringKeyMessage
import dev.bsb.moakeyvim.view.skin.SkinApplier

class ArrowView : ConstraintLayout {

    constructor(context: Context) : super(context) {
        init()
    }
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }
    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        init()
    }

    private lateinit var binding: ArrowViewBinding
    private var isSelecting = false
    private var currentSkin: KeyboardSkin = KeyboardSkin.DEFAULT
    private var enterKeyListener: EnterKeyTouchListener? = null
    private var upKeyListener: RepeatKeyTouchListener? = null
    private var downKeyListener: RepeatKeyTouchListener? = null
    private var leftKeyListener: RepeatKeyTouchListener? = null
    private var rightKeyListener: RepeatKeyTouchListener? = null

    private fun init() {
        inflate(context, R.layout.arrow_view, this)
        binding = ArrowViewBinding.bind(this)
        setOnTouchListeners()
        currentSkin = SettingsPreferences.getKeyboardSkin(context)
        SkinApplier.apply(this, currentSkin)
    }

    fun refreshOneHandMode() {
        val isReduced = SettingsPreferences.getOneHandMode(context).isReduced
        binding.copyAllKey.setText(
            if (isReduced) R.string.key_copy_all_two_line else R.string.key_copy_all
        )
        binding.selectAllKey.setText(
            if (isReduced) R.string.key_select_all_two_line else R.string.key_select_all
        )
        binding.cutAllKey.setText(
            if (isReduced) R.string.key_cut_all_two_line else R.string.key_cut_all
        )
    }

    fun setSelectingOrToggleSelecting(selecting: Boolean? = null) {
        isSelecting = selecting ?: !isSelecting
        val color = if (isSelecting) {
            SkinApplier.fgAccentColor(context, currentSkin)
        } else {
            SkinApplier.fgColor(context, currentSkin)
        }
        listOf(
            binding.areaSelectKey, binding.homeKey, binding.endKey,
            binding.upKey, binding.downKey, binding.leftKey, binding.rightKey,
        ).forEach { it.setTextColor(color) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setOnTouchListeners() {
        binding.apply {
            copyAllKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.COPY_ALL))
            )
            copyKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.COPY))
            )
            upKeyListener = RepeatKeyTouchListener(context) {
                SpecialKeyMessage(if (isSelecting) SpecialKey.SELECT_ARROW_UP else SpecialKey.ARROW_UP)
            }
            upKey.setOnTouchListener(upKeyListener)
            cutKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.CUT))
            )
            cutAllKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.CUT_ALL))
            )
            homeKey.setOnTouchListener(
                FunctionalKeyTouchListener(context) {
                    SpecialKeyMessage(
                        if (isSelecting) SpecialKey.SELECT_HOME else SpecialKey.HOME
                    )
                }
            )
            selectAllKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.SELECT_ALL))
            )
            leftKeyListener = RepeatKeyTouchListener(context) {
                SpecialKeyMessage(if (isSelecting) SpecialKey.SELECT_ARROW_LEFT else SpecialKey.ARROW_LEFT)
            }
            leftKey.setOnTouchListener(leftKeyListener)
            areaSelectKey.setOnTouchListener(
                FunctionalKeyTouchListener(context) {
                    setSelectingOrToggleSelecting()
                    null
                }
            )
            rightKeyListener = RepeatKeyTouchListener(context) {
                SpecialKeyMessage(if (isSelecting) SpecialKey.SELECT_ARROW_RIGHT else SpecialKey.ARROW_RIGHT)
            }
            rightKey.setOnTouchListener(rightKeyListener)
            endKey.setOnTouchListener(
                FunctionalKeyTouchListener(context) {
                    SpecialKeyMessage(
                        if (isSelecting) SpecialKey.SELECT_END else SpecialKey.END
                    )
                }
            )
            deleteKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.DELETE))
            )
            downKeyListener = RepeatKeyTouchListener(context) {
                SpecialKeyMessage(if (isSelecting) SpecialKey.SELECT_ARROW_DOWN else SpecialKey.ARROW_DOWN)
            }
            downKey.setOnTouchListener(downKeyListener)
            pasteKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.PASTE))
            )
            backspaceKey.setOnTouchListener(
                RepeatKeyTouchListener(context, SpecialKeyMessage(SpecialKey.BACKSPACE))
            )
            hanjaNumberPunctuationKey.setOnTouchListener(
                SimpleKeyTouchListener(
                    context, SpecialKeyMessage(SpecialKey.HANJA_NUMBER_PUNCTUATION)
                )
            )
            spaceKey.setOnTouchListener(SpaceKeyTouchListener(context))
            enterKeyListener?.cancel()
            enterKeyListener = EnterKeyTouchListener(context)
            enterKey.setOnTouchListener(enterKeyListener)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        enterKeyListener?.cancel()
        upKeyListener?.endRepeat()
        downKeyListener?.endRepeat()
        leftKeyListener?.endRepeat()
        rightKeyListener?.endRepeat()
    }

}