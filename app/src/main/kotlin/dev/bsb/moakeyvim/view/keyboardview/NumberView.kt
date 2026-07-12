package dev.bsb.moakeyvim.view.keyboardview

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.config.Config
import dev.bsb.moakeyvim.databinding.NumberViewBinding
import dev.bsb.moakeyvim.view.message.SpecialKey
import dev.bsb.moakeyvim.view.keytouchlistener.EnterKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.QwertyKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.RepeatKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SimpleKeyTouchListener
import dev.bsb.moakeyvim.quickphrase.NumberLongKey
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.view.keytouchlistener.SpaceKeyTouchListener
import dev.bsb.moakeyvim.view.message.SpecialKeyMessage
import dev.bsb.moakeyvim.view.message.StringKeyMessage
import dev.bsb.moakeyvim.view.preview.KeyPreviewController
import dev.bsb.moakeyvim.view.preview.QuickPhraseMenuPopup
import dev.bsb.moakeyvim.view.skin.SkinApplier

class NumberView : ConstraintLayout, KoinComponent {

    private val config: Config by inject()

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

    var onEditNumberLongKeyRequest: ((NumberLongKey) -> Unit)? = null

    private lateinit var binding: NumberViewBinding
    private var previewController: KeyPreviewController? = null
    private val numberKeyListeners = mutableListOf<QwertyKeyTouchListener>()
    private var enterKeyListener: EnterKeyTouchListener? = null
    private val prefs by lazy {
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val numberPrefKeys = NumberLongKey.values().map { it.prefKey }.toSet()
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in numberPrefKeys && ::binding.isInitialized) {
            updateKeyHints()
        }
    }

    private fun init() {
        inflate(context, R.layout.number_view, this)
        binding = NumberViewBinding.bind(this)
        previewController = KeyPreviewController({ false }, SettingsPreferences.getKeyboardSkin(context))
        setOnTouchListeners()
        SkinApplier.apply(this, SettingsPreferences.getKeyboardSkin(context))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setOnTouchListeners() {
        numberKeyListeners.clear()
        binding.apply {
            plusKey.setOnTouchListener(SimpleKeyTouchListener(context, StringKeyMessage("+")))
            minusKey.setOnTouchListener(SimpleKeyTouchListener(context, StringKeyMessage("-")))
            asteriskKey.setOnTouchListener(SimpleKeyTouchListener(context, StringKeyMessage("*")))
            dotKey.setOnTouchListener(SimpleKeyTouchListener(context, StringKeyMessage(".")))
            slashKey.setOnTouchListener(SimpleKeyTouchListener(context, StringKeyMessage("/")))
            backspaceKey.setOnTouchListener(
                RepeatKeyTouchListener(context, SpecialKeyMessage(SpecialKey.BACKSPACE))
            )
            hanjaNumberPunctuationKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.HANJA_NUMBER_PUNCTUATION))
            )
            spaceKey.setOnTouchListener(SpaceKeyTouchListener(context))
            enterKeyListener?.cancel()
            enterKeyListener = EnterKeyTouchListener(context)
            enterKey.setOnTouchListener(enterKeyListener)

            listOf(
                oneKey to NumberLongKey.NUM_1, twoKey to NumberLongKey.NUM_2,
                threeKey to NumberLongKey.NUM_3, fourKey to NumberLongKey.NUM_4,
                fiveKey to NumberLongKey.NUM_5, sixKey to NumberLongKey.NUM_6,
                sevenKey to NumberLongKey.NUM_7, eightKey to NumberLongKey.NUM_8,
                nineKey to NumberLongKey.NUM_9, zeroKey to NumberLongKey.NUM_0,
            ).forEach { (view, longKey) ->
                view.keyHint = longKey.getPhrase(context).take(1)
                val popup = QuickPhraseMenuPopup(context)
                val listener = QwertyKeyTouchListener(
                    context,
                    previewController,
                    longKeyProvider = { longKey.getPhrase(context) },
                    onTap = { StringKeyMessage(longKey.digit) },
                    quickPhraseMenuPopup = popup,
                    onEdit = { onEditNumberLongKeyRequest?.invoke(longKey) },
                )
                numberKeyListeners.add(listener)
                view.setOnTouchListener(listener)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateKeyHints()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        previewController?.cancel()
        numberKeyListeners.forEach { it.cancel() }
        enterKeyListener?.cancel()
    }

    private fun updateKeyHints() {
        binding.apply {
            listOf(
                oneKey to NumberLongKey.NUM_1, twoKey to NumberLongKey.NUM_2,
                threeKey to NumberLongKey.NUM_3, fourKey to NumberLongKey.NUM_4,
                fiveKey to NumberLongKey.NUM_5, sixKey to NumberLongKey.NUM_6,
                sevenKey to NumberLongKey.NUM_7, eightKey to NumberLongKey.NUM_8,
                nineKey to NumberLongKey.NUM_9, zeroKey to NumberLongKey.NUM_0,
            ).forEach { (view, longKey) ->
                view.keyHint = longKey.getPhrase(context).take(1)
            }
        }
    }

}