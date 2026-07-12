package dev.bsb.moakeyvim.view.keyboardview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import dev.bsb.moakeyvim.OpenMoaIME
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.config.Config
import dev.bsb.moakeyvim.config.HangulInputMode
import dev.bsb.moakeyvim.config.HapticStrength
import dev.bsb.moakeyvim.config.SoundVolume
import dev.bsb.moakeyvim.view.feedback.KeyFeedbackPlayer
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.view.message.SpecialKey
import dev.bsb.moakeyvim.databinding.OpenMoaViewBinding
import dev.bsb.moakeyvim.databinding.OpenMoaViewMoakeyBinding
import dev.bsb.moakeyvim.view.keytouchlistener.CrossKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.EnterKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.JaumKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.RepeatKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SimpleKeyTouchListener
import dev.bsb.moakeyvim.view.keytouchlistener.SpaceKeyTouchListener
import dev.bsb.moakeyvim.quickphrase.PhraseKey
import dev.bsb.moakeyvim.quickphrase.UserCharKey
import dev.bsb.moakeyvim.view.keytouchlistener.QwertyKeyTouchListener
import dev.bsb.moakeyvim.view.message.SpecialKeyMessage
import dev.bsb.moakeyvim.view.message.StringKeyMessage
import dev.bsb.moakeyvim.quickphrase.QuickPhraseKey
import dev.bsb.moakeyvim.quickphrase.QuickPhraseRepository
import dev.bsb.moakeyvim.view.preview.KeyPreviewController
import dev.bsb.moakeyvim.view.preview.QuickPhraseMenuPopup
import androidx.core.content.ContextCompat
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.view.skin.SkinApplier

class OpenMoaView : ConstraintLayout, KoinComponent {

    private val config: Config by inject()
    private val feedbackPlayer: KeyFeedbackPlayer by inject()

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

    private val broadcastManager = LocalBroadcastManager.getInstance(context)
    internal var isMoakeyMode = false
        private set
    internal var moeumKeyVisible = true
        private set
    private var twoHandBinding: OpenMoaViewBinding? = null
    private var moakeyBinding: OpenMoaViewMoakeyBinding? = null
    private var touchedMoeum: String? = null
    private var moeumKeyBgPressed: android.graphics.drawable.Drawable? = null
    private var moeumKeyBgNormal: android.graphics.drawable.Drawable? = null
    private lateinit var previewController: KeyPreviewController
    private var enterKeyListener: EnterKeyTouchListener? = null
    var jaumPreviewResolver: ((String) -> String)? = null
    var onEditPhraseRequest: ((PhraseKey) -> Unit)? = null

    private fun resolveJaumPreview(key: String): String = jaumPreviewResolver?.invoke(key) ?: key

    private fun init() {
        val skin = SettingsPreferences.getKeyboardSkin(context)
        previewController = KeyPreviewController({ config.keyPreviewEnabled }, skin)
        val mode = SettingsPreferences.getHangulInputMode(context)
        isMoakeyMode = mode.isMoakeyLayout
        moeumKeyVisible = mode.showsMoeumKey
        if (isMoakeyMode) {
            inflate(context, R.layout.open_moa_view_moakey, this)
            moakeyBinding = OpenMoaViewMoakeyBinding.bind(this)
            setMoakeyTouchListeners()
        } else {
            inflate(context, R.layout.open_moa_view, this)
            twoHandBinding = OpenMoaViewBinding.bind(this)
            setTwoHandTouchListeners()
        }
        updateQuickPhraseBadges()
        updateUserCharLabels()
        SkinApplier.apply(this, skin)
        val emojiTint = ColorStateList.valueOf(SkinApplier.fgColor(context, skin))
        twoHandBinding?.emojiKey?.foregroundTintList = emojiTint
        moakeyBinding?.emojiKey?.foregroundTintList = emojiTint
        moeumKeyBgPressed = SkinApplier.buildKeyDrawable(context, skin, pressed = true)
        moeumKeyBgNormal = SkinApplier.buildKeyDrawable(context, skin, pressed = false)
        refreshEmojiIcon()
    }

    fun refreshEmojiIcon() {
        if (SettingsPreferences.getOneHandMode(context).isReduced) {
            val base = ContextCompat.getDrawable(context, R.drawable.ic_emoji) ?: return
            val sizePx = (24 * 0.8f * resources.displayMetrics.density).toInt()
            fun scaledEmoji() = LayerDrawable(arrayOf(base.mutate()))
                .also { it.setLayerSize(0, sizePx, sizePx) }
            twoHandBinding?.emojiKey?.foreground = scaledEmoji()
            moakeyBinding?.emojiKey?.foreground = scaledEmoji()
        } else {
            twoHandBinding?.emojiKey?.foreground = ContextCompat.getDrawable(context, R.drawable.ic_emoji)
            moakeyBinding?.emojiKey?.foreground = ContextCompat.getDrawable(context, R.drawable.ic_emoji)
        }
    }

    fun refreshQuickPhraseBadges() {
        updateQuickPhraseBadges()
    }

    fun refreshUserCharLabels() {
        updateUserCharLabels()
    }

    private fun updateQuickPhraseBadges() {
        twoHandBinding?.apply {
            ssangbieupBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGBIEUP)
            ssangjieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGJIEUT)
            ssangdigeutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGDIGEUT)
            ssanggiyeokBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGGIYEOK)
            ssangsiotBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGSIOT)
            kieukBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.KIEUK)
            tieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.TIEUT)
            chieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.CHIEUT)
            pieupBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.PIEUP)
        }
        moakeyBinding?.apply {
            ssangbieupBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGBIEUP)
            ssangjieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGJIEUT)
            ssangdigeutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGDIGEUT)
            ssanggiyeokBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGGIYEOK)
            ssangsiotBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.SSANGSIOT)
            kieukBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.KIEUK)
            tieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.TIEUT)
            chieutBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.CHIEUT)
            pieupBadge.text = QuickPhraseRepository.getFirstChar(context, QuickPhraseKey.PIEUP)
        }
    }

    private fun updateUserCharLabels() {
        twoHandBinding?.apply {
            tildeKey.text = UserCharKey.TILDE.getPhrase(context)
            caretKey.text = UserCharKey.CARET.getPhrase(context)
            semicolonKey.text = UserCharKey.SEMICOLON.getPhrase(context)
            asteriskKey.text = UserCharKey.ASTERISK.getPhrase(context)
        }
        moakeyBinding?.apply {
            tildeKey.text = UserCharKey.TILDE.getPhrase(context)
            caretKey.text = UserCharKey.CARET.getPhrase(context)
            semicolonKey.text = UserCharKey.SEMICOLON.getPhrase(context)
            asteriskKey.text = UserCharKey.ASTERISK.getPhrase(context)
            exclamationKey.text = UserCharKey.EXCLAMATION.getPhrase(context)
            questionKey.text = UserCharKey.QUESTION.getPhrase(context)
            dotKey.text = UserCharKey.DOT.getPhrase(context)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        previewController.cancel()
        enterKeyListener?.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setTwoHandTouchListeners() {
        val b = twoHandBinding ?: return
        val quickPhraseMenuPopup = QuickPhraseMenuPopup(context)
        val resolver: (String) -> String = ::resolveJaumPreview
        val phraseListener = { key: String, phraseKey: QuickPhraseKey ->
            JaumKeyTouchListener(context, key, previewController, phraseKey, quickPhraseMenuPopup, jaumPreviewResolver = resolver)
                .also { it.onEditPhraseRequest = { k -> onEditPhraseRequest?.invoke(k) } }
        }
        val userCharListener = { userCharKey: UserCharKey ->
            QwertyKeyTouchListener(
                context, previewController,
                longKeyProvider = { userCharKey.getPhrase(context) },
                onTap = { StringKeyMessage(userCharKey.getPhrase(context)) },
                quickPhraseMenuPopup = QuickPhraseMenuPopup(context),
                onEdit = { onEditPhraseRequest?.invoke(userCharKey) },
            )
        }
        b.apply {
            tildeKey.setOnTouchListener(userCharListener(UserCharKey.TILDE))
            ssangbieupKey.setOnTouchListener(phraseListener("ㅃ", QuickPhraseKey.SSANGBIEUP))
            ssangjieutKey.setOnTouchListener(phraseListener("ㅉ", QuickPhraseKey.SSANGJIEUT))
            ssangdigeutKey.setOnTouchListener(phraseListener("ㄸ", QuickPhraseKey.SSANGDIGEUT))
            ssanggiyeokKey.setOnTouchListener(phraseListener("ㄲ", QuickPhraseKey.SSANGGIYEOK))
            ssangsiotKey.setOnTouchListener(phraseListener("ㅆ", QuickPhraseKey.SSANGSIOT))
            emojiKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.EMOJI))
            )
            caretKey.setOnTouchListener(userCharListener(UserCharKey.CARET))
            bieupKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅂ", previewController, numberChar = "1", jaumPreviewResolver = resolver))
            jieutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅈ", previewController, numberChar = "2", jaumPreviewResolver = resolver))
            digeutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄷ", previewController, numberChar = "3", jaumPreviewResolver = resolver))
            giyeokKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄱ", previewController, numberChar = "4", jaumPreviewResolver = resolver))
            siotKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅅ", previewController, numberChar = "5", jaumPreviewResolver = resolver))
            backspaceKey.setOnTouchListener(
                RepeatKeyTouchListener(context, SpecialKeyMessage(SpecialKey.BACKSPACE))
            )
            semicolonKey.setOnTouchListener(userCharListener(UserCharKey.SEMICOLON))
            mieumKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅁ", previewController, numberChar = "6", jaumPreviewResolver = resolver))
            nieunKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄴ", previewController, numberChar = "7", jaumPreviewResolver = resolver))
            ieungKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅇ", previewController, numberChar = "8", jaumPreviewResolver = resolver))
            rieulKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄹ", previewController, numberChar = "9", jaumPreviewResolver = resolver))
            hieutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅎ", previewController, numberChar = "0", jaumPreviewResolver = resolver))
            asteriskKey.setOnTouchListener(userCharListener(UserCharKey.ASTERISK))
            kieukKey.setOnTouchListener(phraseListener("ㅋ", QuickPhraseKey.KIEUK))
            tieutKey.setOnTouchListener(phraseListener("ㅌ", QuickPhraseKey.TIEUT))
            chieutKey.setOnTouchListener(phraseListener("ㅊ", QuickPhraseKey.CHIEUT))
            pieupKey.setOnTouchListener(phraseListener("ㅍ", QuickPhraseKey.PIEUP))
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

    private fun applyMoeumKeyVisibility() {
        val b = moakeyBinding ?: return
        if (moeumKeyVisible) return
        b.moeumKey.visibility = View.GONE
        val parent = b.moeumKey.parent as? ConstraintLayout ?: return
        val cs = ConstraintSet()
        cs.clone(parent)
        cs.clear(R.id.moeumKey, ConstraintSet.LEFT)
        cs.clear(R.id.moeumKey, ConstraintSet.RIGHT)
        cs.setHorizontalWeight(R.id.moeumKey, 0f)
        cs.connect(R.id.spaceKey, ConstraintSet.RIGHT, R.id.enterKey, ConstraintSet.LEFT)
        cs.connect(R.id.enterKey, ConstraintSet.LEFT, R.id.spaceKey, ConstraintSet.RIGHT)
        cs.setHorizontalWeight(R.id.emojiKey, 1f)
        cs.setHorizontalWeight(R.id.spaceKey, 4f)
        cs.setHorizontalWeight(R.id.enterKey, 1f)
        cs.applyTo(parent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setMoakeyTouchListeners() {
        val b = moakeyBinding ?: return
        applyMoeumKeyVisibility()
        val quickPhraseMenuPopup = QuickPhraseMenuPopup(context)
        val resolver: (String) -> String = ::resolveJaumPreview
        val phraseListener = { key: String, phraseKey: QuickPhraseKey ->
            JaumKeyTouchListener(context, key, previewController, phraseKey, quickPhraseMenuPopup, jaumPreviewResolver = resolver)
                .also { it.onEditPhraseRequest = { k -> onEditPhraseRequest?.invoke(k) } }
        }
        val userCharListener = { userCharKey: UserCharKey ->
            QwertyKeyTouchListener(
                context, previewController,
                longKeyProvider = { userCharKey.getPhrase(context) },
                onTap = { StringKeyMessage(userCharKey.getPhrase(context)) },
                quickPhraseMenuPopup = QuickPhraseMenuPopup(context),
                onEdit = { onEditPhraseRequest?.invoke(userCharKey) },
            )
        }
        b.apply {
            tildeKey.setOnTouchListener(userCharListener(UserCharKey.TILDE))
            ssangbieupKey.setOnTouchListener(phraseListener("ㅃ", QuickPhraseKey.SSANGBIEUP))
            ssangjieutKey.setOnTouchListener(phraseListener("ㅉ", QuickPhraseKey.SSANGJIEUT))
            ssangdigeutKey.setOnTouchListener(phraseListener("ㄸ", QuickPhraseKey.SSANGDIGEUT))
            ssanggiyeokKey.setOnTouchListener(phraseListener("ㄲ", QuickPhraseKey.SSANGGIYEOK))
            ssangsiotKey.setOnTouchListener(phraseListener("ㅆ", QuickPhraseKey.SSANGSIOT))
            exclamationKey.setOnTouchListener(userCharListener(UserCharKey.EXCLAMATION))
            caretKey.setOnTouchListener(userCharListener(UserCharKey.CARET))
            bieupKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅂ", previewController, numberChar = "1", jaumPreviewResolver = resolver))
            jieutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅈ", previewController, numberChar = "2", jaumPreviewResolver = resolver))
            digeutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄷ", previewController, numberChar = "3", jaumPreviewResolver = resolver))
            giyeokKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄱ", previewController, numberChar = "4", jaumPreviewResolver = resolver))
            siotKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅅ", previewController, numberChar = "5", jaumPreviewResolver = resolver))
            questionKey.setOnTouchListener(userCharListener(UserCharKey.QUESTION))
            semicolonKey.setOnTouchListener(userCharListener(UserCharKey.SEMICOLON))
            mieumKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅁ", previewController, numberChar = "6", jaumPreviewResolver = resolver))
            nieunKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄴ", previewController, numberChar = "7", jaumPreviewResolver = resolver))
            ieungKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅇ", previewController, numberChar = "8", jaumPreviewResolver = resolver))
            rieulKey.setOnTouchListener(JaumKeyTouchListener(context, "ㄹ", previewController, numberChar = "9", jaumPreviewResolver = resolver))
            hieutKey.setOnTouchListener(JaumKeyTouchListener(context, "ㅎ", previewController, numberChar = "0", jaumPreviewResolver = resolver))
            dotKey.setOnTouchListener(userCharListener(UserCharKey.DOT))
            asteriskKey.setOnTouchListener(userCharListener(UserCharKey.ASTERISK))
            kieukKey.setOnTouchListener(phraseListener("ㅋ", QuickPhraseKey.KIEUK))
            tieutKey.setOnTouchListener(phraseListener("ㅌ", QuickPhraseKey.TIEUT))
            chieutKey.setOnTouchListener(phraseListener("ㅊ", QuickPhraseKey.CHIEUT))
            pieupKey.setOnTouchListener(phraseListener("ㅍ", QuickPhraseKey.PIEUP))
            backspaceKey.setOnTouchListener(
                RepeatKeyTouchListener(context, SpecialKeyMessage(SpecialKey.BACKSPACE))
            )
            emojiKey.setOnTouchListener(
                SimpleKeyTouchListener(context, SpecialKeyMessage(SpecialKey.EMOJI))
            )
            hanjaNumberPunctuationKey.setOnTouchListener(
                SimpleKeyTouchListener(
                    context, SpecialKeyMessage(SpecialKey.HANJA_NUMBER_PUNCTUATION)
                )
            )
            spaceKey.setOnTouchListener(SpaceKeyTouchListener(context))
            moeumKey.setOnTouchListener(
                CrossKeyTouchListener(
                    context,
                    listOf(
                        StringKeyMessage("ㆍ"),
                        StringKeyMessage("ㅡ"),
                        StringKeyMessage("ㆍ"),
                        StringKeyMessage("ㅣ"),
                    ),
                    previewController,
                )
            )
            enterKeyListener = EnterKeyTouchListener(context)
            enterKey.setOnTouchListener(enterKeyListener)
        }
    }

    private val touchYCorrection by lazy { 8f * resources.displayMetrics.density }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        ev.offsetLocation(0f, -touchYCorrection)
        return try {
            dispatchCorrectedTouchEvent(ev)
        } finally {
            ev.offsetLocation(0f, touchYCorrection)
        }
    }

    private fun dispatchCorrectedTouchEvent(ev: MotionEvent): Boolean {
        if (!isMoakeyMode) {
            val b = twoHandBinding ?: return super.dispatchTouchEvent(ev)
            touchedMoeum.let { moeum ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        if (ev.action == MotionEvent.ACTION_DOWN ||
                            (ev.action == MotionEvent.ACTION_MOVE && touchedMoeum != null)
                        ) {
                            b.iKey.apply {
                                if (ev.x in x..x + width && ev.y in y..y + height) {
                                    if (moeum != "ㅣ") {
                                        background = moeumKeyBgPressed
                                        b.euKey.background = moeumKeyBgNormal
                                        b.araeaKey.background = moeumKeyBgNormal
                                        config.hapticStrength.let { s ->
                                            if (s != HapticStrength.OFF) feedbackPlayer.playHaptic(s.durationMs, s.amplitude)
                                        }
                                        config.soundVolume.let { v ->
                                            if (v != SoundVolume.OFF) feedbackPlayer.playSound(config.soundType.effectId, v.volume)
                                        }
                                        if (moeum != null) {
                                            sendKeyMessage(StringKeyMessage(moeum))
                                        }
                                        previewController.show(this, "ㅣ")
                                    }
                                    touchedMoeum = "ㅣ"
                                    return true
                                }
                            }
                            b.euKey.apply {
                                if (ev.x in x..x + width && ev.y in y..y + height) {
                                    if (moeum != "ㅡ") {
                                        background = moeumKeyBgPressed
                                        b.iKey.background = moeumKeyBgNormal
                                        b.araeaKey.background = moeumKeyBgNormal
                                        config.hapticStrength.let { s ->
                                            if (s != HapticStrength.OFF) feedbackPlayer.playHaptic(s.durationMs, s.amplitude)
                                        }
                                        config.soundVolume.let { v ->
                                            if (v != SoundVolume.OFF) feedbackPlayer.playSound(config.soundType.effectId, v.volume)
                                        }
                                        if (moeum != null) {
                                            sendKeyMessage(StringKeyMessage(moeum))
                                        }
                                        previewController.show(this, "ㅡ")
                                    }
                                    touchedMoeum = "ㅡ"
                                    return true
                                }
                            }
                            b.araeaKey.apply {
                                if (ev.x in x..x + width && ev.y in y..y + height) {
                                    if (moeum != "ㆍ") {
                                        background = moeumKeyBgPressed
                                        b.iKey.background = moeumKeyBgNormal
                                        b.euKey.background = moeumKeyBgNormal
                                        config.hapticStrength.let { s ->
                                            if (s != HapticStrength.OFF) feedbackPlayer.playHaptic(s.durationMs, s.amplitude)
                                        }
                                        config.soundVolume.let { v ->
                                            if (v != SoundVolume.OFF) feedbackPlayer.playSound(config.soundType.effectId, v.volume)
                                        }
                                        if (moeum != null) {
                                            sendKeyMessage(StringKeyMessage(moeum))
                                        }
                                        previewController.show(this, "ㆍ")
                                    }
                                    touchedMoeum = "ㆍ"
                                    return true
                                }
                            }
                        }
                        Unit
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moeum != null) {
                            b.iKey.background = moeumKeyBgNormal
                            b.euKey.background = moeumKeyBgNormal
                            b.araeaKey.background = moeumKeyBgNormal
                            sendKeyMessage(StringKeyMessage(moeum))
                            touchedMoeum = null
                            previewController.hide()
                            return true
                        }
                        Unit
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (moeum != null) {
                            b.iKey.background = moeumKeyBgNormal
                            b.euKey.background = moeumKeyBgNormal
                            b.araeaKey.background = moeumKeyBgNormal
                            sendKeyMessage(StringKeyMessage(moeum))
                            touchedMoeum = null
                            previewController.cancel()
                        }
                        Unit
                    }
                    else -> Unit
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun sendKeyMessage(keyMessage: StringKeyMessage) {
        broadcastManager.sendBroadcast(
            Intent(OpenMoaIME.INTENT_ACTION).apply {
                putExtra(OpenMoaIME.EXTRA_NAME, keyMessage.key)
            }
        )
    }

}
