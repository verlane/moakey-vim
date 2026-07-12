package dev.bsb.moakeyvim.view.keyboardview.qwerty

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.constraintlayout.widget.ConstraintLayout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.config.Config
import dev.bsb.moakeyvim.view.message.SpecialKey
import dev.bsb.moakeyvim.databinding.QuertyViewBinding
import dev.bsb.moakeyvim.quickphrase.NumberLongKey
import dev.bsb.moakeyvim.quickphrase.QwertyLongKey
import dev.bsb.moakeyvim.quickphrase.QwertyLongKeyRepository
import dev.bsb.moakeyvim.view.keytouchlistener.CrossKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.EnterKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.FunctionalKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.QwertyKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.RepeatKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SimpleKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SpaceKeyTouchListener
import dev.bsb.moakeyvim.view.message.SpecialKeyMessage
import dev.bsb.moakeyvim.view.message.StringKeyMessage
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.quickphrase.PhraseKey
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.view.preview.KeyPreviewController
import dev.bsb.moakeyvim.view.preview.QuickPhraseMenuPopup
import dev.bsb.moakeyvim.view.skin.SkinApplier

open class QuertyView : ConstraintLayout, KoinComponent {

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

    var onEditPhraseRequest: ((PhraseKey) -> Unit)? = null
    private var shiftKeyStatus = ShiftKeyStatus.DISABLED
    protected lateinit var binding: QuertyViewBinding
        private set
    private var previewController: KeyPreviewController? = null
    private var currentSkin: KeyboardSkin = KeyboardSkin.DEFAULT
    private val configurableLongKeyListeners = mutableListOf<QwertyKeyTouchListener>()
    private val numberRowListeners = mutableListOf<QwertyKeyTouchListener>()
    private var enterKeyListener: EnterKeyTouchListener? = null
    private val prefs by lazy {
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val allConfigurablePrefKeys =
        QwertyLongKey.values().map { it.prefKey }.toSet() +
        NumberLongKey.values().map { it.prefKey }.toSet()
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in allConfigurablePrefKeys && ::binding.isInitialized) {
            updateConfigurableKeyHints()
        }
    }
    private val configurableLongKeyPairs by lazy {
        listOf(
            binding.zKey to QwertyLongKey.Z, binding.xKey to QwertyLongKey.X,
            binding.cKey to QwertyLongKey.C, binding.vKey to QwertyLongKey.V,
            binding.bKey to QwertyLongKey.B, binding.nKey to QwertyLongKey.N,
            binding.mKey to QwertyLongKey.M,
        )
    }
    private val numberRowPairs by lazy {
        listOf(
            binding.oneKey to NumberLongKey.NUM_1, binding.twoKey to NumberLongKey.NUM_2,
            binding.threeKey to NumberLongKey.NUM_3, binding.fourKey to NumberLongKey.NUM_4,
            binding.fiveKey to NumberLongKey.NUM_5, binding.sixKey to NumberLongKey.NUM_6,
            binding.sevenKey to NumberLongKey.NUM_7, binding.eightKey to NumberLongKey.NUM_8,
            binding.nineKey to NumberLongKey.NUM_9, binding.zeroKey to NumberLongKey.NUM_0,
        )
    }

    protected open fun keyList(): List<List<String>> = DEFAULT_KEY_LIST

    private fun init() {
        inflate(context, R.layout.querty_view, this)
        binding = QuertyViewBinding.bind(this)
        currentSkin = SettingsPreferences.getKeyboardSkin(context)
        previewController = KeyPreviewController({ config.keyPreviewEnabled }, currentSkin)
        setShiftStatus(ShiftKeyStatus.DISABLED, true)
        setOnTouchListeners()
        updateConfigurableKeyHints()
        SkinApplier.apply(this, currentSkin)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        previewController?.cancel()
        configurableLongKeyListeners.forEach { it.cancel() }
        numberRowListeners.forEach { it.cancel() }
        enterKeyListener?.cancel()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            configurableLongKeyListeners.forEach { it.cancel() }
            numberRowListeners.forEach { it.cancel() }
        }
    }

    private fun updateConfigurableKeyHints() {
        configurableLongKeyPairs.forEach { (view, longKeyEnum) ->
            view.keyHint = QwertyLongKeyRepository.getPhrase(context, longKeyEnum).take(1)
        }
        numberRowPairs.forEach { (view, longKey) ->
            view.keyHint = longKey.getPhrase(context).take(1)
        }
    }

    private fun setShiftStatus(status: ShiftKeyStatus, isInitialize: Boolean = false) {
        if (shiftKeyStatus == status && !isInitialize) {
            return
        }
        val prevShiftEnabled = shiftKeyStatus != ShiftKeyStatus.DISABLED
        val isShiftEnabled = status != ShiftKeyStatus.DISABLED
        if (prevShiftEnabled != isShiftEnabled || isInitialize) {
            listOf(
                binding.qKey, binding.wKey, binding.eKey, binding.rKey, binding.tKey, binding.yKey,
                binding.uKey, binding.iKey, binding.oKey, binding.pKey, binding.aKey, binding.sKey,
                binding.dKey, binding.fKey, binding.gKey, binding.hKey, binding.jKey, binding.kKey,
                binding.lKey, binding.zKey, binding.xKey, binding.cKey, binding.vKey, binding.bKey,
                binding.nKey, binding.mKey,
            ).mapIndexed { index, view ->
                view.text = keyList()[if (isShiftEnabled) 1 else 0][index]
            }
            binding.shiftKey.setTextColor(
                if (isShiftEnabled) {
                    SkinApplier.fgAccentColor(context, currentSkin)
                } else {
                    SkinApplier.fgColor(context, currentSkin)
                }
            )
        }
        binding.shiftKey.text = if (status == ShiftKeyStatus.LOCKED) "⬆︎" else "⇧"
        shiftKeyStatus = status
    }

    fun setShiftEnabledAutomatically(isEnabled: Boolean) {
        if (shiftKeyStatus != ShiftKeyStatus.LOCKED) {
            setShiftStatus(if (isEnabled) ShiftKeyStatus.AUTO_ENABLED else ShiftKeyStatus.DISABLED)
        }
    }

    protected fun refreshKeyLabels() {
        if (::binding.isInitialized) setShiftStatus(shiftKeyStatus, isInitialize = true)
    }

    protected fun isBindingInitialized(): Boolean = ::binding.isInitialized

    @SuppressLint("ClickableViewAccessibility")
    private fun setOnTouchListeners() {
        numberRowListeners.clear()
        numberRowPairs.forEach { (view, longKey) ->
            val popup = QuickPhraseMenuPopup(context)
            val listener = QwertyKeyTouchListener(
                context,
                previewController,
                longKeyProvider = { longKey.getPhrase(context) },
                onTap = {
                    val key = view.text.toString()
                    setShiftStatus(when (shiftKeyStatus) {
                        ShiftKeyStatus.ENABLED -> ShiftKeyStatus.DISABLED
                        else -> shiftKeyStatus
                    })
                    StringKeyMessage(key)
                },
                quickPhraseMenuPopup = popup,
                onEdit = { onEditPhraseRequest?.invoke(longKey) },
            )
            numberRowListeners.add(listener)
            view.setOnTouchListener(listener)
        }
        setAlphaKeyTouchListeners()
        binding.apply {
            shiftKey.setOnTouchListener(
                FunctionalKeyTouchListener(context, triggerWhenActionUp = false) {
                    setShiftStatus(
                        when (shiftKeyStatus) {
                            ShiftKeyStatus.DISABLED -> ShiftKeyStatus.ENABLED
                            ShiftKeyStatus.ENABLED -> ShiftKeyStatus.LOCKED
                            ShiftKeyStatus.AUTO_ENABLED,
                            ShiftKeyStatus.LOCKED -> ShiftKeyStatus.DISABLED
                        }
                    )
                    null
                }
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
            commaQuestionDotExclamationKey.setOnTouchListener(
                CrossKeyTouchListener(
                    context,
                    listOf(
                        StringKeyMessage(","),
                        StringKeyMessage("!"),
                        StringKeyMessage("."),
                        StringKeyMessage("?"),
                    ),
                    previewController,
                )
            )
            enterKeyListener = EnterKeyTouchListener(context)
            enterKey.setOnTouchListener(enterKeyListener)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setAlphaKeyTouchListeners() {
        val fixedLongKeyPairs = listOf(
            binding.qKey to "#", binding.wKey to "&", binding.eKey to "%",
            binding.rKey to "+", binding.tKey to "=", binding.yKey to "_",
            binding.uKey to "\\", binding.iKey to "|", binding.oKey to "<", binding.pKey to ">",
            binding.aKey to "-", binding.sKey to "@", binding.dKey to "*",
            binding.fKey to "^", binding.gKey to ":", binding.hKey to ";",
            binding.jKey to "(", binding.kKey to ")", binding.lKey to "~",
        )
        fixedLongKeyPairs.forEach { (view, longKey) ->
            view.apply {
                keyHint = longKey
                setOnTouchListener(QwertyKeyTouchListener(
                    context,
                    previewController,
                    { longKey },
                    onTap = {
                        val key = text.toString()
                        setShiftStatus(when (shiftKeyStatus) {
                            ShiftKeyStatus.ENABLED -> ShiftKeyStatus.DISABLED
                            else -> shiftKeyStatus
                        })
                        // 단모음 자판에서 자모가 없는 자리(빈 키)는 무반응
                        if (key.isEmpty()) null else StringKeyMessage(key)
                    }
                ))
            }
        }
        configurableLongKeyListeners.clear()
        configurableLongKeyPairs.forEach { (view, longKeyEnum) ->
            val popup = QuickPhraseMenuPopup(context)
            val listener = QwertyKeyTouchListener(
                context,
                previewController,
                { QwertyLongKeyRepository.getPhrase(context, longKeyEnum) },
                onTap = {
                    val key = view.text.toString()
                    setShiftStatus(when (shiftKeyStatus) {
                        ShiftKeyStatus.ENABLED -> ShiftKeyStatus.DISABLED
                        else -> shiftKeyStatus
                    })
                    if (key.isEmpty()) null else StringKeyMessage(key)
                },
                quickPhraseMenuPopup = popup,
                onEdit = { onEditPhraseRequest?.invoke(longKeyEnum) }
            )
            configurableLongKeyListeners.add(listener)
            view.apply {
                keyHint = QwertyLongKeyRepository.getPhrase(context, longKeyEnum).take(1)
                setOnTouchListener(listener)
            }
        }
    }

    companion object {
        private val DEFAULT_KEY_LIST = listOf(
            listOf(
                "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
                "a", "s", "d", "f", "g", "h", "j", "k", "l",
                "z", "x", "c", "v", "b", "n", "m",
            ),
            listOf(
                "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                "A", "S", "D", "F", "G", "H", "J", "K", "L",
                "Z", "X", "C", "V", "B", "N", "M",
            ),
        )
    }

}