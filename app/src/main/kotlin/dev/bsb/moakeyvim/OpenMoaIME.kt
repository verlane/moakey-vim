package dev.bsb.moakeyvim

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.text.InputType
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.ContextCompat
import androidx.core.view.isEmpty
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import dev.bsb.moakeyvim.config.Config
import dev.bsb.moakeyvim.suggestion.HangulSyllable
import dev.bsb.moakeyvim.suggestion.KoreanPrefixExtractor
import dev.bsb.moakeyvim.suggestion.KoreanSuggestionEngine
import dev.bsb.moakeyvim.suggestion.SuggestionEngine
import dev.bsb.moakeyvim.suggestion.UserWordStore
import dev.bsb.moakeyvim.suggestion.WordTokenizer
import dev.bsb.moakeyvim.config.HangulInputMode
import dev.bsb.moakeyvim.view.feedback.KeyFeedbackPlayer
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.config.OneHandMode
import dev.bsb.moakeyvim.view.keyboardview.qwerty.QuertyKoView
import dev.bsb.moakeyvim.databinding.OpenMoaImeBinding
import dev.bsb.moakeyvim.hangul.HangulAssembler
import dev.bsb.moakeyvim.hardware.HardwareKeyboardController
import dev.bsb.moakeyvim.hotstring.HotstringMatcher
import dev.bsb.moakeyvim.hotstring.HotstringRepository
import dev.bsb.moakeyvim.hotstring.HotstringRule
import dev.bsb.moakeyvim.quickphrase.NumberLongKey
import dev.bsb.moakeyvim.quickphrase.PhraseKey
import dev.bsb.moakeyvim.quickphrase.QuickPhraseKey
import dev.bsb.moakeyvim.quickphrase.UserCharKey
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.bsb.moakeyvim.clipboard.ClipboardEntry
import dev.bsb.moakeyvim.clipboard.ClipboardRepository
import dev.bsb.moakeyvim.settings.SettingsActivity
import dev.bsb.moakeyvim.settings.SettingsPreferences
import dev.bsb.moakeyvim.view.keyboardview.*
import dev.bsb.moakeyvim.view.keyboardview.qwerty.QuertyView
import dev.bsb.moakeyvim.view.message.SpecialKey
import dev.bsb.moakeyvim.view.skin.SkinApplier
import java.io.Serializable
import kotlin.math.roundToInt

class OpenMoaIME : InputMethodService(), KoinComponent {

    private lateinit var binding: OpenMoaImeBinding
    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var keyboardViews: Map<IMEMode, View>
    private val config: Config by inject()
    private val feedbackPlayer: KeyFeedbackPlayer by inject()
    private val suggestionEngine: SuggestionEngine by inject()
    private val koreanSuggestionEngine: KoreanSuggestionEngine by inject()
    private val userWordStore: UserWordStore by inject(named("en"))
    private val koreanUserWordStore: UserWordStore by inject(named("ko"))
    private val hangulAssembler = HangulAssembler()
    private var imeMode = IMEMode.IME_KO
    private var previousImeMode = IMEMode.IME_KO
    private val hardwareKeyboardController by lazy {
        HardwareKeyboardController(
            context = this,
            onToggleLanguageRequested = { toggleLanguage() },
            currentImeMode = { imeMode },
            onJamoInput = { jamo -> dispatchHardwareJamo(jamo) },
            inputConnectionProvider = { currentInputConnection },
        )
    }

    private fun dispatchHardwareJamo(jamo: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(INTENT_ACTION).apply { putExtra(EXTRA_NAME, jamo) }
        )
    }

    private fun isRealInputContext(attribute: EditorInfo?): Boolean {
        if (attribute == null) return false
        if (attribute.inputType == InputType.TYPE_NULL) return false
        return true
    }

    private val inputBindingPollHandler = Handler(Looper.getMainLooper())
    // 일부 기기에서 BT 키보드 연결 해제 시 onUnbindInput/onFinishInput이 호출되지 않아
    // 플로팅 인디케이터가 계속 표시되는 문제를 방어하기 위한 폴러
    private val inputBindingPollRunnable = object : Runnable {
        override fun run() {
            if (currentInputBinding == null || currentInputConnection == null) {
                hardwareKeyboardController.onInputFinished()
                return
            }
            inputBindingPollHandler.postDelayed(this, INPUT_POLL_INTERVAL_MS)
        }
    }

    private fun startInputBindingPoll() {
        inputBindingPollHandler.removeCallbacks(inputBindingPollRunnable)
        inputBindingPollHandler.postDelayed(inputBindingPollRunnable, INPUT_POLL_INTERVAL_MS)
    }

    private fun stopInputBindingPoll() {
        inputBindingPollHandler.removeCallbacks(inputBindingPollRunnable)
    }
    private var composingText = ""
    private var lastSpaceTime = 0L
    private var lastAppliedSkin: KeyboardSkin = KeyboardSkin.DEFAULT
    private var isPasswordField = false
    private var isHotstringEnabled = false
    private var lastHotstringTrigger: String? = null
    private var lastHotstringExpansion: String? = null
    private var hotstringBuffer = ""
    private var lastLearnedWord: String? = null
    private var lastLearnedIsKo: Boolean = false
    private var lastSimpleJamo: String? = null
    private var lastSimpleJamoTime: Long = 0L
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suggestionJob: Job? = null
    private var isSuggestionBarActive = false
    private var isTextSelected = false
    private var isClipboardPanelVisible = false
    private var suggestionLongPressPopup: android.widget.PopupWindow? = null
    private var activeFormEditText: EditText? = null
    private data class TextState(val text: String, val cursor: Int)
    private val formUndoStack = ArrayDeque<TextState>()
    private val formRedoStack = ArrayDeque<TextState>()
    private var isFormUndoRedo = false
    private var pendingUndoState: TextState? = null
    private var currentSuggestions: List<String> = emptyList()
    private var currentHotstringExpansions: Set<String> = emptySet()
    private var clipboardManager: ClipboardManager? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPasswordField && config.clipboardEnabled) {
            getClipboardText()?.let {
                ClipboardRepository.add(this, it)
                if (isClipboardPanelVisible && this::binding.isInitialized) {
                    binding.clipboardPanel.refresh(this)
                }
            }
            // onUpdateSelection보다 나중에 실행되도록 다음 사이클로 미룸
            inputBindingPollHandler.post { refreshClipboardPreviewIfIdle() }
        }
    }

    private fun refreshClipboardPreviewIfIdle() {
        if (!this::binding.isInitialized) return
        if (isPasswordField || !config.clipboardEnabled) return
        if (composingText.isNotEmpty()) return
        if (isTextSelected) return
        if (isClipboardPanelVisible) return
        if (activeFormEditText != null) return
        if (!isSuggestionBarActive) return
        showIdleSuggestionBar(hasSelection = false)
    }

    private fun finishComposing() {
        currentInputConnection?.finishComposingText()
        hangulAssembler.clear()
        composingText = ""
        resetSimpleMultiTap()
    }

    private fun resetSimpleMultiTap() {
        lastSimpleJamo = null
        lastSimpleJamoTime = 0L
    }

    private fun showClipboardPanel() {
        if (!this::binding.isInitialized) return
        if (!config.clipboardEnabled) return
        hideEditForm()
        isClipboardPanelVisible = true
        finishComposing()
        deactivateSuggestionBar()
        if (!isPasswordField) {
            getClipboardText()?.let { ClipboardRepository.add(this, it) }
        }
        ClipboardRepository.purgeExpired(this)
        binding.clipboardPanel.refresh(this)
        binding.clipboardPanel.visibility = View.VISIBLE
        binding.keyboardFrameLayout.visibility = View.INVISIBLE
    }

    private fun hideClipboardPanel() {
        if (!this::binding.isInitialized) return
        isClipboardPanelVisible = false
        binding.clipboardPanel.visibility = View.GONE
        binding.keyboardFrameLayout.visibility = View.VISIBLE
    }

    private fun refreshSuggestions(prefix: String) {
        if (!this::binding.isInitialized) return
        val isEnMode = imeMode == IMEMode.IME_EN
        val isKoMode = imeMode == IMEMode.IME_KO
        val enEnabled = config.wordSuggestionEnabled && isEnMode
        val koEnabled = config.koreanWordSuggestionEnabled && isKoMode
        if (isPasswordField || (!enEnabled && !koEnabled)) {
            deactivateSuggestionBar()
            return
        }
        if (prefix.isEmpty()) {
            showIdleSuggestionBar(isTextSelected)
            return
        }
        suggestionJob?.cancel()
        val capturedIsKoMode = isKoMode
        val capturedComposing = composingText
        val capturedHotstringBuffer = hotstringBuffer
        val capturedUnresolved = if (isKoMode) hangulAssembler.getUnresolved() else null
        suggestionJob = serviceScope.launch {
            // 첫 활성화 시 키 미리보기 dismiss 딜레이 완료 후 표시
            if (config.keyPreviewEnabled && !isSuggestionBarActive) {
                delay(250)
            }
            if (composingText != capturedComposing) return@launch
            val minCount = config.minLearnCount
            val words = if (capturedIsKoMode) {
                koreanSuggestionEngine.suggest(prefix, capturedUnresolved, minCount)
            } else {
                suggestionEngine.suggest(prefix, minCount)
            }
            if (!this@OpenMoaIME::binding.isInitialized) return@launch
            val triggerToMatch = if (capturedIsKoMode) prefix else capturedHotstringBuffer
            val enabledRules = HotstringRepository.getCached(this@OpenMoaIME).filter { it.enabled }

            // 1순위: 트리거 정확 매칭
            val triggerMatched = enabledRules
                .filter { it.trigger == triggerToMatch }
                .map { it.expansion }

            // 2순위: 확장어가 현재 입력(prefix)으로 시작하는 경우
            val syllablePrefix = if (capturedIsKoMode) {
                KoreanPrefixExtractor.extract(prefix, capturedUnresolved).first
            } else {
                prefix
            }
            val chosungPattern = if (capturedIsKoMode && HangulSyllable.isAllChosung(prefix)) {
                prefix
            } else {
                null
            }
            val triggerMatchedTrimmed = triggerMatched.map { it.trim() }.toSet()
            val expansionMatched = enabledRules
                .filter { rule ->
                    rule.expansion.trim() !in triggerMatchedTrimmed &&
                    (
                        (syllablePrefix.length >= 2 && rule.expansion.startsWith(syllablePrefix)) ||
                        (chosungPattern != null && HangulSyllable.matchesChosungPattern(rule.expansion, chosungPattern))
                    )
                }
                .sortedBy { rule ->
                    when {
                        triggerToMatch.startsWith(rule.trigger) -> 0
                        rule.trigger.startsWith(triggerToMatch) -> 1
                        else -> 2
                    }
                }
                .map { it.expansion }  // 원본 유지 (trailing space 포함)

            // trim 기준으로 중복 제거하되 원본 값 보존 — 자동 치환 우선
            val hotstringExpansions = (triggerMatched + expansionMatched).distinctBy { it.trim() }
            val hotstringTrimmedSet = hotstringExpansions.map { it.trim() }.toSet()
            val hotstringExpansionSet = hotstringExpansions.toSet()
            val finalWords = (hotstringExpansions + words.filter { it.trim() !in hotstringTrimmedSet })
                .distinctBy { it.trim() }
                .take(config.maxSuggestionCount)
            if (finalWords.isEmpty()) {
                showIdleSuggestionBar(isTextSelected)
            } else {
                isSuggestionBarActive = true
                currentSuggestions = finalWords
                currentHotstringExpansions = hotstringExpansionSet
                binding.wordSuggestionBar.setSuggestions(finalWords, hotstringExpansionSet)
                binding.wordSuggestionBar.visibility = View.VISIBLE
            }
        }
    }

    private fun deactivateSuggestionBar() {
        if (!this::binding.isInitialized) return
        isSuggestionBarActive = false
        binding.wordSuggestionBar.setSuggestions(emptyList())
        showIdleSuggestionBar(isTextSelected)
    }

    private fun showPhoneNumberKeySuggestions(key: NumberLongKey) {
        if (!this::binding.isInitialized) return
        val phrase = key.getPhrase(this)
        if (phrase.isNotEmpty()) {
            isSuggestionBarActive = true
            currentSuggestions = listOf(phrase)
            currentHotstringExpansions = emptySet()
            binding.wordSuggestionBar.setSuggestions(listOf(phrase))
            binding.wordSuggestionBar.visibility = View.VISIBLE
        }
    }

    private fun showIdleSuggestionBar(hasSelection: Boolean) {
        if (!this::binding.isInitialized) return
        if (isPasswordField) {
            binding.wordSuggestionBar.showEmpty(showClipboardIcon = false)
            binding.wordSuggestionBar.visibility = View.VISIBLE
            return
        }
        if (hasSelection) {
            binding.wordSuggestionBar.showSelectionActions(
                onCut = { sendKeyDownUpEvent(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON) },
                onCopy = { sendKeyDownUpEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON) },
            )
        } else {
            val clipText = getClipboardText()
            if (clipText != null) {
                binding.wordSuggestionBar.showClipboard(clipText) { text ->
                    val formEditText = activeFormEditText
                    if (formEditText != null) {
                        val cursor = formEditText.selectionStart.coerceAtLeast(0)
                        val selEnd = formEditText.selectionEnd.coerceAtLeast(cursor)
                        hangulAssembler.clear()
                        composingText = ""
                        formEditText.text.replace(cursor, selEnd, text)
                        formEditText.setSelection(cursor + text.length)
                        refreshFormSuggestions(formEditText)
                    } else {
                        finishComposing()
                        currentInputConnection?.commitText(text, 1)
                        binding.wordSuggestionBar.showClipboardIconOnly()
                    }
                }
            } else {
                binding.wordSuggestionBar.showEmpty()
            }
        }
        binding.wordSuggestionBar.visibility = View.VISIBLE
    }

    private fun getClipboardText(): String? {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val item = clipboard?.primaryClip?.getItemAt(0) ?: return null
            item.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // Phase 1 + Idea A + Idea B: 단어 커밋 시 학습 (Enter/Space/구두점 공통)
    private fun learnComposingWordOnCommit() {
        if (isPasswordField || composingText.isEmpty()) return
        if (imeMode == IMEMode.IME_EN) {
            val word = WordTokenizer.extractEnglish(composingText) ?: return
            userWordStore.increment(word)
            lastLearnedWord = word
            lastLearnedIsKo = false
            serviceScope.launch {
                // Backspace로 undo됐으면(lastLearnedWord != word) ensureMinCount 건너뜀
                if (suggestionEngine.containsInDictionary(word) && lastLearnedWord == word) {
                    userWordStore.ensureMinCount(word, config.minLearnCount)
                }
            }
        } else if (imeMode == IMEMode.IME_KO) {
            val word = WordTokenizer.normalizeKorean(composingText) ?: return
            val capturedText = composingText
            koreanUserWordStore.increment(word)
            lastLearnedWord = word
            lastLearnedIsKo = true
            serviceScope.launch {
                // stem은 사전 조회용, ensureMinCount는 word(조사 포함 원형) 키로 통일
                val stem = WordTokenizer.extractKorean(capturedText)
                if (stem != null && koreanSuggestionEngine.containsInDictionary(stem) && lastLearnedWord == word) {
                    koreanUserWordStore.ensureMinCount(word, config.minLearnCount)
                }
            }
        }
    }

    // Idea C: 커서 이동 시 사전에 있는 단어만 학습
    private fun learnComposingWordIfInDictionary() {
        if (isPasswordField || composingText.isEmpty()) return
        if (imeMode == IMEMode.IME_EN) {
            val word = WordTokenizer.extractEnglish(composingText) ?: return
            serviceScope.launch {
                if (suggestionEngine.containsInDictionary(word)) {
                    userWordStore.increment(word)
                    userWordStore.ensureMinCount(word, config.minLearnCount)
                }
            }
        } else if (imeMode == IMEMode.IME_KO) {
            val word = WordTokenizer.normalizeKorean(composingText) ?: return
            val capturedText = composingText
            serviceScope.launch {
                // stem은 사전 조회용, increment/ensureMinCount는 word(조사 포함 원형) 키로 통일
                val stem = WordTokenizer.extractKorean(capturedText) ?: word
                if (koreanSuggestionEngine.containsInDictionary(stem)) {
                    koreanUserWordStore.increment(word)
                    koreanUserWordStore.ensureMinCount(word, config.minLearnCount)
                }
            }
        }
    }

    private fun onSuggestionPicked(word: String, isHotstring: Boolean) {
        val formEditText = activeFormEditText
        if (formEditText != null) {
            val cursor = formEditText.selectionStart.coerceAtLeast(0)
            val textBefore = formEditText.text.substring(0, cursor)
            val wordStart = textBefore.indexOfLast { it == ' ' || it == '\n' } + 1
            hangulAssembler.clear()
            formEditText.text.replace(wordStart, cursor, word)
            formEditText.setSelection(wordStart + word.length)
            refreshFormSuggestions(formEditText)
            return
        }
        if (isHotstring) {
            if (imeMode == IMEMode.IME_EN) {
                val trigger = hotstringBuffer
                val currentComposingLen = composingText.length
                val committedPartLen = (trigger.length - currentComposingLen).coerceAtLeast(0)
                hangulAssembler.clear()
                composingText = ""
                currentInputConnection?.beginBatchEdit()
                try {
                    currentInputConnection?.finishComposingText()
                    currentInputConnection?.deleteSurroundingText(committedPartLen + currentComposingLen, 0)
                    currentInputConnection?.commitText(word, 1)
                } finally {
                    currentInputConnection?.endBatchEdit()
                }
            } else {
                hangulAssembler.clear()
                composingText = ""
                currentInputConnection?.commitText(word, 1)
            }
            hotstringBuffer = ""
        } else if (imeMode == IMEMode.IME_KO_PHONE || imeMode == IMEMode.IME_EN_PHONE) {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            try {
                val charBefore = ic.getTextBeforeCursor(1, 0)?.toString()
                if (charBefore != null && NumberLongKey.fromDigit(charBefore) != null) {
                    ic.deleteSurroundingText(1, 0)
                }
                ic.commitText(word, 1)
            } finally {
                ic.endBatchEdit()
            }
        } else if (imeMode == IMEMode.IME_KO) {
            koreanUserWordStore.increment(word)
            hangulAssembler.clear()
            composingText = ""
            currentInputConnection?.commitText(word + " ", 1)
        } else {
            userWordStore.increment(word)
            composingText = ""
            hangulAssembler.clear()
            currentInputConnection?.commitText(word + " ", 1)
        }
        isTextSelected = false
        showIdleSuggestionBar(false)
        setShiftAutomatically()
    }

    private fun onSuggestionLongClick(word: String) {
        val isKo = imeMode == IMEMode.IME_KO
        val store = if (isKo) koreanUserWordStore else userWordStore
        val anchorView = binding.wordSuggestionBar

        val menuView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            setPadding(dp8, dp8, dp8, dp8)
        }
        suggestionLongPressPopup?.dismiss()
        val popup = android.widget.PopupWindow(
            menuView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        )
        suggestionLongPressPopup = popup

        fun addItem(label: String, action: () -> Unit) {
            val dp12 = (12 * resources.displayMetrics.density).toInt()
            val tv = android.widget.TextView(this).apply {
                text = label
                textSize = 15f
                setPadding(dp12, dp12, dp12, dp12)
                background = obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                ).let { it.getDrawable(0).also { _ -> it.recycle() } }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
            }
            menuView.addView(tv)
        }

        addItem(getString(R.string.suggestion_long_click_remove)) {
            store.remove(word)
            refreshAfterSuggestionEdit(word)
        }
        addItem(getString(R.string.suggestion_long_click_blacklist)) {
            store.addToBlacklist(word)
            refreshAfterSuggestionEdit(word)
        }

        menuView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val xoff = anchorView.width / 2 - menuView.measuredWidth / 2
        val yoff = -(menuView.measuredHeight + anchorView.height)
        popup.showAsDropDown(anchorView, xoff, yoff)
    }

    private fun refreshAfterSuggestionEdit(removedWord: String) {
        val updated = currentSuggestions.filter { it != removedWord }
        currentSuggestions = updated
        if (updated.isNotEmpty()) {
            binding.wordSuggestionBar.setSuggestions(updated, currentHotstringExpansions)
        } else if (composingText.isNotEmpty()) {
            refreshSuggestions(composingText)
        } else {
            showIdleSuggestionBar(isTextSelected)
        }
    }

    private inline fun <reified T : Serializable> getKeyFromIntent(intent: Intent): T? {
        val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_NAME, Serializable::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_NAME)
        }
        return extra as? T
    }

    private fun sendKeyDownUpEvent(keyCode: Int, metaState: Int = 0, withShift: Boolean = false) {
        val ic = currentInputConnection ?: return
        var eventTime = SystemClock.uptimeMillis()
        if (withShift) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
        ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                metaState,
                0,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            )
        )
        eventTime = SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_UP,
                keyCode,
                0,
                0,
                0,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            )
        )
        if (withShift) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT))
        }
    }

    private fun isTextEmpty(): Boolean {
        val text = currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)
        return (text?.text ?: "") == ""
    }

    override fun onCreate() {
        super.onCreate()
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                suggestionLongPressPopup?.dismiss()
                val key = getKeyFromIntent<String>(intent)
                    ?: getKeyFromIntent<SpecialKey>(intent)
                    ?: return
                val formEditText = activeFormEditText
                if (formEditText != null && key != SpecialKey.LANGUAGE && key != SpecialKey.HANJA_NUMBER_PUNCTUATION && key != SpecialKey.OPEN_SETTINGS) {
                    handleFormKey(key, formEditText)
                    return@onReceive
                }
                val beforeComposingText = composingText
                when (key) {
                    is SpecialKey -> {
                        // 단모음 multi-tap 상태는 자모 키 연속 입력에서만 의미가 있음
                        resetSimpleMultiTap()
                        // Process for special key
                        when (key) {
                            SpecialKey.BACKSPACE -> {
                                lastLearnedWord?.let {
                                    if (lastLearnedIsKo) koreanUserWordStore.decrement(it)
                                    else userWordStore.decrement(it)
                                    lastLearnedWord = null
                                }
                                val undoTrigger = lastHotstringTrigger
                                val undoExpansion = lastHotstringExpansion
                                if (undoTrigger != null && undoExpansion != null) {
                                    lastHotstringTrigger = null
                                    lastHotstringExpansion = null
                                    hotstringBuffer = ""
                                    finishComposing()
                                    val ic = currentInputConnection ?: return@onReceive
                                    ic.deleteSurroundingText(undoExpansion.length, 0)
                                    ic.commitText(undoTrigger, 1)
                                    return@onReceive
                                }
                                hotstringBuffer = hotstringBuffer.dropLast(1)
                                val unresolved = hangulAssembler.getUnresolved()
                                if (unresolved != null) {
                                    composingText = composingText.substring(
                                        0, composingText.length - unresolved.length
                                    )
                                    hangulAssembler.removeLastJamo(isQwertyKoActive())
                                    hangulAssembler.getUnresolved()?.let {
                                        composingText += it
                                    }
                                } else {
                                    if (composingText.isEmpty()) {
                                        sendKeyDownUpEvent(KeyEvent.KEYCODE_DEL)
                                    } else {
                                        composingText = composingText.substring(
                                            0, composingText.lastIndex
                                        )
                                    }
                                }
                            }
                            SpecialKey.ENTER -> {
                                hotstringBuffer = ""
                                learnComposingWordOnCommit()
                                finishComposing()
                                val action = currentInputEditorInfo.imeOptions and (
                                    EditorInfo.IME_MASK_ACTION or
                                    EditorInfo.IME_FLAG_NO_ENTER_ACTION
                                )
                                when (action) {
                                    EditorInfo.IME_ACTION_GO,
                                    EditorInfo.IME_ACTION_NEXT,
                                    EditorInfo.IME_ACTION_SEARCH,
                                    EditorInfo.IME_ACTION_SEND,
                                    EditorInfo.IME_ACTION_DONE -> {
                                        currentInputConnection?.performEditorAction(action)
                                    }
                                    else -> {
                                        sendKeyDownUpEvent(KeyEvent.KEYCODE_ENTER)
                                    }
                                }
                            }
                            SpecialKey.LANGUAGE -> {
                                toggleLanguage()
                            }
                            SpecialKey.HANJA_NUMBER_PUNCTUATION -> {
                                setKeyboard(
                                    when (imeMode) {
                                        IMEMode.IME_KO,
                                        IMEMode.IME_KO_ARROW,
                                        IMEMode.IME_KO_PHONE,
                                        IMEMode.IME_EMOJI -> IMEMode.IME_KO_PUNCTUATION
                                        IMEMode.IME_KO_PUNCTUATION -> IMEMode.IME_KO_NUMBER
                                        IMEMode.IME_KO_NUMBER -> IMEMode.IME_KO

                                        IMEMode.IME_EN,
                                        IMEMode.IME_EN_ARROW,
                                        IMEMode.IME_EN_PHONE -> IMEMode.IME_EN_PUNCTUATION
                                        IMEMode.IME_EN_PUNCTUATION -> IMEMode.IME_EN_NUMBER
                                        IMEMode.IME_EN_NUMBER -> IMEMode.IME_EN
                                    }
                                )
                            }
                            SpecialKey.ARROW -> {
                                setKeyboard(
                                    when (imeMode) {
                                        IMEMode.IME_KO,
                                        IMEMode.IME_KO_NUMBER,
                                        IMEMode.IME_KO_PUNCTUATION,
                                        IMEMode.IME_KO_ARROW,
                                        IMEMode.IME_KO_PHONE,
                                        IMEMode.IME_EMOJI -> IMEMode.IME_KO_ARROW
                                        IMEMode.IME_EN,
                                        IMEMode.IME_EN_NUMBER,
                                        IMEMode.IME_EN_PUNCTUATION,
                                        IMEMode.IME_EN_ARROW,
                                        IMEMode.IME_EN_PHONE -> IMEMode.IME_EN_ARROW
                                    }
                                )
                            }
                            SpecialKey.ARROW_UP -> {
                                hotstringBuffer = ""
                                learnComposingWordIfInDictionary()
                                if (composingText.isNotEmpty()) finishComposing()
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_UP)
                                }
                            }
                            SpecialKey.ARROW_LEFT -> {
                                hotstringBuffer = ""
                                learnComposingWordIfInDictionary()
                                if (composingText.isNotEmpty()) finishComposing()
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                                }
                            }
                            SpecialKey.ARROW_RIGHT -> {
                                hotstringBuffer = ""
                                learnComposingWordIfInDictionary()
                                if (composingText.isNotEmpty()) finishComposing()
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                                }
                            }
                            SpecialKey.ARROW_DOWN -> {
                                hotstringBuffer = ""
                                learnComposingWordIfInDictionary()
                                if (composingText.isNotEmpty()) finishComposing()
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_DOWN)
                                }
                            }
                            SpecialKey.COPY_ALL -> {
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.COPY -> {
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.CUT_ALL -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.CUT -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.HOME -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(
                                    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON
                                )
                            }
                            SpecialKey.END -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(
                                    KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON
                                )
                            }
                            SpecialKey.DELETE -> {
                                hotstringBuffer = hotstringBuffer.dropLast(1)
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_DEL)
                            }
                            SpecialKey.PASTE -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.SELECT_ALL -> {
                                hotstringBuffer = ""
                                sendKeyDownUpEvent(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
                            }
                            SpecialKey.SELECT_ARROW_UP -> {
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_UP, withShift = true)
                                }
                            }
                            SpecialKey.SELECT_ARROW_LEFT -> {
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_LEFT, withShift = true)
                                }
                            }
                            SpecialKey.SELECT_ARROW_RIGHT -> {
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(
                                        KeyEvent.KEYCODE_DPAD_RIGHT, withShift = true
                                    )
                                }
                            }
                            SpecialKey.SELECT_ARROW_DOWN -> {
                                if (!isTextEmpty()) {
                                    sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_DOWN, withShift = true)
                                }
                            }
                            SpecialKey.SELECT_END -> {
                                sendKeyDownUpEvent(
                                    KeyEvent.KEYCODE_MOVE_END, KeyEvent.META_CTRL_ON, true
                                )
                            }
                            SpecialKey.SELECT_HOME -> {
                                sendKeyDownUpEvent(
                                    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.META_CTRL_ON, true
                                )
                            }
                            SpecialKey.EMOJI -> {
                                if (imeMode == IMEMode.IME_EMOJI) {
                                    setKeyboard(previousImeMode)
                                } else {
                                    previousImeMode = imeMode
                                    setKeyboard(IMEMode.IME_EMOJI)
                                }
                            }
                            SpecialKey.OPEN_SETTINGS -> {
                                hideEditForm()
                                startActivity(
                                    Intent(this@OpenMoaIME, SettingsActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                            SpecialKey.SHOW_IME_PICKER -> {
                                finishComposing()
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                imm?.showInputMethodPicker()
                            }
                            SpecialKey.CLIPBOARD_OPEN -> showClipboardPanel()
                        }
                    }
                    is String -> {
                        if (key.matches(Regex("^[A-Za-z]$"))) {
                            // Process for Alphabet key
                            composingText += key
                            hotstringBuffer = (hotstringBuffer + key).takeLast(50)
                        } else if (key.matches(HangulAssembler.JAMO_REGEX)) {
                            // Process for Jamo key
                            // 단모음 모드 multi-tap 합성: 같은 자모를 임계 시간 내 두 번 누르면
                            // 직전 자모를 합성 자모로 교체 (음절 보존)
                            val now = SystemClock.uptimeMillis()
                            val withinThreshold = now - lastSimpleJamoTime <= SIMPLE_MULTI_TAP_MS
                            val combined = if (
                                isSimpleQwertyKoActive() && withinThreshold && lastSimpleJamo == key
                            ) SIMPLE_MULTI_TAP_MAP[key] else null
                            hangulAssembler.getUnresolved()?.let {
                                composingText = composingText.substring(
                                    0, composingText.length - it.length
                                )
                            }
                            if (combined != null) {
                                hangulAssembler.replaceLastJamo(combined)?.let {
                                    composingText += it
                                }
                                lastSimpleJamo = combined
                            } else {
                                hangulAssembler.appendJamo(key)?.let {
                                    composingText += it
                                }
                                hangulAssembler.getUnresolved()?.let {
                                    composingText += it
                                }
                                lastSimpleJamo = key
                            }
                            lastSimpleJamoTime = now
                            hotstringBuffer = (hotstringBuffer + key).takeLast(50)
                        } else {
                            // Process for another key
                            resetSimpleMultiTap()
                            lastHotstringTrigger = null
                            lastHotstringExpansion = null
                            lastLearnedWord = null
                            if ((key == " " || key in WORD_COMMIT_PUNCTUATION)) {
                                learnComposingWordOnCommit()
                            }
                            if (key != " ") {
                                hotstringBuffer = (hotstringBuffer + key).takeLast(50)
                            } else {
                                hotstringBuffer = ""
                            }
                            finishComposing()
                            val ic = currentInputConnection ?: return@onReceive
                            if (key == " ") {
                                if (tryExpandHotstring()) {
                                    lastSpaceTime = 0L
                                } else {
                                    val autoEnabled = SettingsPreferences.getAutoSpacePeriod(this@OpenMoaIME)
                                    val now = SystemClock.elapsedRealtime()
                                    if (autoEnabled && now - lastSpaceTime < 1000L &&
                                        ic.getTextBeforeCursor(1, 0) == " ") {
                                        ic.deleteSurroundingText(1, 0)
                                        ic.commitText(". ", 1)
                                        lastSpaceTime = 0L
                                    } else {
                                        ic.commitText(key, 1)
                                        lastSpaceTime = if (autoEnabled) now else 0L
                                    }
                                }
                            } else {
                                ic.commitText(key, 1)
                                lastSpaceTime = 0L
                            }
                        }
                    }
                }
                if (this@OpenMoaIME::binding.isInitialized) binding.wordSuggestionBar.resetUndoRedo()
                if (beforeComposingText != composingText) {
                    currentInputConnection?.setComposingText(composingText, 1)
                }
                if (composingText.isNotEmpty()) {
                    refreshSuggestions(composingText)
                } else {
                    suggestionJob?.cancel()
                    val isPhoneMode = imeMode == IMEMode.IME_KO_PHONE || imeMode == IMEMode.IME_EN_PHONE
                    val phoneKey = if (isPhoneMode && key is String) {
                        NumberLongKey.fromDigit(key)
                    } else null
                    if (phoneKey != null) {
                        showPhoneNumberKeySuggestions(phoneKey)
                    } else {
                        showIdleSuggestionBar(false)
                    }
                }
                setShiftAutomatically()
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            broadcastReceiver, IntentFilter(INTENT_ACTION)
        )
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        val isKorean = newSubtype.languageTag.startsWith("ko")
        if (!isKorean && !SettingsPreferences.getEnglishKeyboardEnabled(this)) {
            switchToNextInputMethod(false)
            return
        }
        setKeyboard(if (isKorean) IMEMode.IME_KO else IMEMode.IME_EN)
    }

    private fun setKeyboard(mode: IMEMode) {
        if (this::keyboardViews.isInitialized && this::binding.isInitialized) {
            finishComposing()
            hotstringBuffer = ""
            deactivateSuggestionBar()
            keyboardViews[mode]?.let {
                when (it) {
                    is PunctuationView -> it.setPageOrNextPage(0)
                    is PhoneView -> it.setPageOrNextPage(0)
                    is ArrowView -> {
                        it.setSelectingOrToggleSelecting(false)
                        it.refreshOneHandMode()
                    }
                }
                binding.keyboardFrameLayout.setKeyboardView(it)
            }
        }
        imeMode = mode
        hardwareKeyboardController.onLanguageChanged()
    }

    private fun toggleLanguage() {
        switchToNextInputMethod(false)
    }

    private fun setShiftAutomatically() {
        if (!config.autoCapitalizeEnglish) return
        if (imeMode != IMEMode.IME_EN) return
        keyboardViews[imeMode]?.let { view ->
            if (view is QuertyView) {
                currentInputConnection?.let { inputConnection ->
                    view.setShiftEnabledAutomatically(
                        inputConnection.getCursorCapsMode(currentInputEditorInfo.inputType) != 0
                    )
                }
            }
        }
    }

    private fun returnFromNonStringKeyboard() {
        when (imeMode) {
            IMEMode.IME_KO,
            IMEMode.IME_KO_PUNCTUATION,
            IMEMode.IME_KO_NUMBER,
            IMEMode.IME_KO_ARROW,
            IMEMode.IME_KO_PHONE,
            IMEMode.IME_EMOJI -> setKeyboard(IMEMode.IME_KO)
            IMEMode.IME_EN,
            IMEMode.IME_EN_PUNCTUATION,
            IMEMode.IME_EN_NUMBER,
            IMEMode.IME_EN_ARROW,
            IMEMode.IME_EN_PHONE -> setKeyboard(IMEMode.IME_EN)
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        super.onCreateInputView()
        lastAppliedSkin = SettingsPreferences.getKeyboardSkin(this)
        keyboardViews = buildKeyboardViews()
        val view = layoutInflater.inflate(R.layout.open_moa_ime, null)
        binding = OpenMoaImeBinding.bind(view)
        binding.wordSuggestionBar.onPick = ::onSuggestionPicked
        binding.wordSuggestionBar.onWordLongClick = { word -> onSuggestionLongClick(word) }
        binding.wordSuggestionBar.onCursorLeft = {
            val formEditText = activeFormEditText
            if (formEditText != null) {
                val selStart = formEditText.selectionStart.coerceAtLeast(0)
                val selEnd = formEditText.selectionEnd.coerceAtLeast(selStart)
                if (selStart != selEnd) {
                    formEditText.setSelection(selStart)
                } else if (selStart > 0) {
                    formEditText.setSelection(selStart - 1)
                }
            } else if (!isTextEmpty()) {
                sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_LEFT)
            }
        }
        binding.wordSuggestionBar.onCursorRight = {
            val formEditText = activeFormEditText
            if (formEditText != null) {
                val selStart = formEditText.selectionStart.coerceAtLeast(0)
                val selEnd = formEditText.selectionEnd.coerceAtLeast(selStart)
                if (selStart != selEnd) {
                    formEditText.setSelection(selEnd)
                } else if (selEnd < formEditText.text.length) {
                    formEditText.setSelection(selEnd + 1)
                }
            } else if (!isTextEmpty()) {
                sendKeyDownUpEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
        }
        binding.wordSuggestionBar.onUndoRedo = { isUndo ->
            val formEditText = activeFormEditText
            if (formEditText != null) {
                val stack = if (isUndo) formUndoStack else formRedoStack
                val counterStack = if (isUndo) formRedoStack else formUndoStack
                if (stack.isNotEmpty()) {
                    val current = TextState(formEditText.text.toString(), formEditText.selectionStart.coerceAtLeast(0))
                    counterStack.addLast(current)
                    isFormUndoRedo = true
                    val state = stack.removeLast()
                    formEditText.setText(state.text)
                    formEditText.setSelection(state.cursor.coerceAtMost(state.text.length))
                    isFormUndoRedo = false
                    refreshFormSuggestions(formEditText)
                }
            } else {
                if (isUndo) currentInputConnection?.performContextMenuAction(android.R.id.undo)
                else currentInputConnection?.performContextMenuAction(android.R.id.redo)
            }
        }
        binding.wordSuggestionBar.onSettings = {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        binding.wordSuggestionBar.onOpenClipboardPanel = { showClipboardPanel() }
        binding.clipboardPanel.onPaste = { text ->
            finishComposing()
            currentInputConnection?.commitText(text, 1)
            hideClipboardPanel()
        }
        binding.clipboardPanel.onClose = { hideClipboardPanel() }
        binding.clipboardPanel.onEdit = { entry ->
            hideClipboardPanel()
            showClipboardEditForm(entry)
        }
        binding.clipboardPanel.onAddHotstring = { entry ->
            hideClipboardPanel()
            showHotstringAddForm(entry)
        }
        binding.clipboardPanel.onOpenUrl = { url ->
            runCatching {
                val normalizedUrl = if (Uri.parse(url).scheme == null) "https://$url" else url
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { android.util.Log.w("OpenMoaIME", "Failed to open URL: $url", it) }
        }
        binding.clipboardPanel.onOpenEmail = { email ->
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { android.util.Log.w("OpenMoaIME", "Failed to open email: $email", it) }
        }
        (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.onEditPhraseRequest = { key ->
            showPhraseEditForm(key)
        }
        (keyboardViews[IMEMode.IME_EN] as? QuertyView)?.onEditPhraseRequest = { key ->
            showPhraseEditForm(key)
        }
        (keyboardViews[IMEMode.IME_KO_NUMBER] as? NumberView)?.onEditNumberLongKeyRequest = { key ->
            showPhraseEditForm(key)
        }
        (keyboardViews[IMEMode.IME_KO_PUNCTUATION] as? PunctuationView)?.onEditNumberLongKeyRequest = { key ->
            showPhraseEditForm(key)
        }
        (keyboardViews[IMEMode.IME_KO_PHONE] as? PhoneView)?.onEditNumberLongKeyRequest = { key ->
            showPhraseEditForm(key)
        }
        applyKeyboardLayout()
        setKeyboard(imeMode)
        applyGestureNavigationSpacing(view)
        return view
    }

    private data class KoLayout(val useQwerty: Boolean, val simpleQwerty: Boolean)

    private fun isQwertyKoActive(): Boolean = keyboardViews[IMEMode.IME_KO] is QuertyKoView

    private fun resolveKoLayout(): KoLayout {
        val savedMode = SettingsPreferences.getHangulInputMode(this)
        if (savedMode.isQwertyLayout) {
            return KoLayout(useQwerty = true, simpleQwerty = savedMode.isSimpleQwerty)
        }
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val landscapeLayout = SettingsPreferences.getLandscapeKoLayout(this)
        if (isLandscape && landscapeLayout.isQwerty) {
            return KoLayout(useQwerty = true, simpleQwerty = landscapeLayout.isSimple)
        }
        return KoLayout(useQwerty = false, simpleQwerty = false)
    }

    private fun buildKoView(): View {
        val layout = resolveKoLayout()
        return if (layout.useQwerty) {
            QuertyKoView(this).also { it.simple = layout.simpleQwerty }
        } else {
            OpenMoaView(this).also { it.jaumPreviewResolver = hangulAssembler::previewWithAppended }
        }
    }

    private fun buildKeyboardViews(): MutableMap<IMEMode, View> {
        val punctuationView = PunctuationView(this)
        val numberView = NumberView(this)
        val arrowView = ArrowView(this)
        val phoneView = PhoneView(this)
        val emojiView = EmojiView(this)
        return mutableMapOf(
            IMEMode.IME_KO to buildKoView(),
            IMEMode.IME_EN to QuertyView(this),
            IMEMode.IME_KO_PUNCTUATION to punctuationView,
            IMEMode.IME_EN_PUNCTUATION to punctuationView,
            IMEMode.IME_KO_NUMBER to numberView,
            IMEMode.IME_EN_NUMBER to numberView,
            IMEMode.IME_KO_ARROW to arrowView,
            IMEMode.IME_EN_ARROW to arrowView,
            IMEMode.IME_KO_PHONE to phoneView,
            IMEMode.IME_EN_PHONE to phoneView,
            IMEMode.IME_EMOJI to emojiView,
        )
    }

    private fun refreshSkinIfNeeded() {
        val currentSkin = SettingsPreferences.getKeyboardSkin(this)
        if (currentSkin == lastAppliedSkin) return
        lastAppliedSkin = currentSkin
        keyboardViews = buildKeyboardViews()
        setKeyboard(imeMode)
    }

    private fun refreshKoViewIfNeeded() {
        val layout = resolveKoLayout()
        val currentView = keyboardViews[IMEMode.IME_KO]
        val currentQwerty = currentView as? QuertyKoView
        if (layout.useQwerty != (currentQwerty != null)) {
            keyboardViews = keyboardViews + (IMEMode.IME_KO to buildKoView())
            wireKoViewCallbacks()
            return
        }
        if (currentQwerty != null) {
            if (currentQwerty.simple != layout.simpleQwerty) {
                keyboardViews = keyboardViews + (IMEMode.IME_KO to buildKoView())
                wireKoViewCallbacks()
            }
            return
        }
        val openMoa = currentView as? OpenMoaView ?: return
        val savedMode = SettingsPreferences.getHangulInputMode(this)
        if (openMoa.isMoakeyMode != savedMode.isMoakeyLayout ||
            openMoa.moeumKeyVisible != savedMode.showsMoeumKey) {
            keyboardViews = keyboardViews + (IMEMode.IME_KO to buildKoView())
            wireKoViewCallbacks()
        }
    }

    private fun wireKoViewCallbacks() {
        val koView = keyboardViews[IMEMode.IME_KO]
        (koView as? OpenMoaView)?.onEditPhraseRequest = { key ->
            showPhraseEditForm(key)
        }
        (koView as? QuertyView)?.onEditPhraseRequest = { key ->
            showPhraseEditForm(key)
        }
    }

    private fun tryExpandHotstring(): Boolean {
        if (!isHotstringEnabled) return false
        if (isPasswordField) return false
        val ic = currentInputConnection ?: return false
        val rules = HotstringRepository.getCached(this)
        val maxLen = HotstringMatcher.bufferLengthNeeded(rules)
        if (maxLen == 0) return false

        // Chrome의 인라인 자동완성은 selectionStart(사용자 입력 끝) ~ selectionEnd(완성 텍스트 끝)를
        // 선택 상태로 표시한다. 이 경우 getTextBeforeCursor가 완성 텍스트까지 포함해 반환하여
        // 트리거 매칭이 실패하므로, selectionStart 이전 텍스트로만 매칭한다.
        val et: ExtractedText? = ic.getExtractedText(ExtractedTextRequest(), 0)
        val selStart = et?.selectionStart ?: 0
        val selEnd = et?.selectionEnd ?: 0
        val etText = et?.text
        // et.text가 null이면 선택 범위를 신뢰할 수 없으므로 0으로 처리
        val inlineCompletionLen = if (selEnd > selStart && etText != null) selEnd - selStart else 0

        val buffer = if (inlineCompletionLen > 0 && etText != null) {
            val localSelStart = (selStart - et!!.startOffset).coerceAtLeast(0)
            etText.substring(maxOf(0, localSelStart - maxLen), localSelStart)
        } else {
            ic.getTextBeforeCursor(maxLen, 0)?.toString() ?: return false
        }

        val match = HotstringMatcher.findMatch(buffer, rules) ?: return false

        ic.beginBatchEdit()
        try {
            if (inlineCompletionLen > 0) {
                // 커서가 selStart 쪽에 있을 수 있으므로 selEnd로 명시적 이동 후 삭제
                ic.setSelection(selEnd, selEnd)
            }
            ic.deleteSurroundingText(match.trigger.length + inlineCompletionLen, 0)
            ic.commitText(match.expansion, 1)
            lastHotstringTrigger = match.trigger
            lastHotstringExpansion = match.expansion
        } finally {
            ic.endBatchEdit()
        }
        return true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        suggestionLongPressPopup?.dismiss()
        finishComposing()
        hotstringBuffer = ""
        lastLearnedWord = null
        lastLearnedIsKo = false
        isHotstringEnabled = SettingsPreferences.getHotstringEnabled(this)
        val inputType = info?.inputType ?: 0
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        // TYPE_TEXT_VARIATION_URI(0x10)와 TYPE_NUMBER_VARIATION_PASSWORD(0x10)가 같은 값이라
        // inputClass로 구분 필요 (URI 필드가 password로 잘못 잡히는 버그 방지, 예: Chrome URL=0x80011)
        // PHONE/DATETIME 클래스에는 password variation이 없으므로 else->false 안전
        isPasswordField = when (inputClass) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        if (!isPasswordField && config.clipboardEnabled) {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        }
        refreshSkinIfNeeded()
        refreshKoViewIfNeeded()
        (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.refreshQuickPhraseBadges()
        (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.refreshUserCharLabels()
        (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.        refreshEmojiIcon()
        applyKeyboardLayout()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val subtype = imm?.currentInputMethodSubtype
        var isKorean = subtype?.languageTag?.startsWith("ko") ?: true
        if (!isKorean && !SettingsPreferences.getEnglishKeyboardEnabled(this)) {
            isKorean = true
        }
        when ((info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> {
                setKeyboard(if (isKorean) IMEMode.IME_KO_NUMBER else IMEMode.IME_EN_NUMBER)
            }
            InputType.TYPE_CLASS_PHONE -> {
                setKeyboard(if (isKorean) IMEMode.IME_KO_PHONE else IMEMode.IME_EN_PHONE)
            }
            else -> {
                setShiftAutomatically()
                setKeyboard(if (isKorean) IMEMode.IME_KO else IMEMode.IME_EN)
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        if (composingText.isNotEmpty() &&
            (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
        ) {
            finishComposing()
            hotstringBuffer = ""
        }
        val hasSelection = newSelStart != newSelEnd
        if (hasSelection != isTextSelected) {
            isTextSelected = hasSelection
            if (isSuggestionBarActive && composingText.isEmpty()) {
                showIdleSuggestionBar(hasSelection)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        suggestionLongPressPopup?.dismiss()
        suggestionLongPressPopup = null
        deactivateSuggestionBar()
        hideEditForm()
        if (isClipboardPanelVisible) hideClipboardPanel()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager = null
        userWordStore.flush()
        koreanUserWordStore.flush()
        if (finishingInput) hardwareKeyboardController.onInputFinished()
    }

    private fun showClipboardEditForm(entry: ClipboardEntry) {
        if (!this::binding.isInitialized) return
        finishComposing()
        val container = binding.imeEditFormContainer
        container.removeAllViews()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val editText = EditText(this).apply {
            setText(entry.text)
            setSelection(entry.text.length)
            isSingleLine = false
            minLines = 1
            maxLines = 4
            gravity = android.view.Gravity.TOP
            isVerticalScrollBarEnabled = true
        }
        val saveBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_save)
            setOnClickListener {
                val newText = editText.text.toString()
                if (newText.isBlank()) {
                    Toast.makeText(this@OpenMoaIME, getString(R.string.clipboard_edit_empty_error), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ClipboardRepository.update(this@OpenMoaIME, entry.id, newText)
                hideEditForm()
                showClipboardPanel()
            }
        }
        val cancelBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_cancel)
            setOnClickListener {
                hideEditForm()
                showClipboardPanel()
            }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(editText, lp)
        container.addView(btnRow, lp)
        container.visibility = View.VISIBLE
        applyFormSkin(container, listOf(editText), listOf(cancelBtn, saveBtn))
        formUndoStack.clear()
        formRedoStack.clear()
        attachFormUndoWatcher(editText)
        editText.requestFocus()
        activeFormEditText = editText
        refreshFormSuggestions(editText)
    }

    private fun showHotstringAddForm(entry: ClipboardEntry) {
        if (!this::binding.isInitialized) return
        finishComposing()
        val container = binding.imeEditFormContainer
        container.removeAllViews()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val triggerEditText = EditText(this).apply {
            hint = getString(R.string.settings_hotstring_trigger_hint)
            setSingleLine()
        }
        val expansionEditText = EditText(this).apply {
            setText(entry.text)
            setSelection(entry.text.length)
            hint = getString(R.string.settings_hotstring_expansion_hint)
            setSingleLine()
        }
        val saveBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_save)
            setOnClickListener {
                val trigger = triggerEditText.text.toString().trim()
                val expansion = expansionEditText.text.toString()
                when {
                    trigger.isEmpty() -> Toast.makeText(this@OpenMoaIME, getString(R.string.settings_hotstring_trigger_empty), Toast.LENGTH_SHORT).show()
                    expansion.isEmpty() -> Toast.makeText(this@OpenMoaIME, getString(R.string.settings_hotstring_expansion_empty), Toast.LENGTH_SHORT).show()
                    else -> {
                        val existing = HotstringRepository.getAll(this@OpenMoaIME).firstOrNull { it.trigger == trigger }
                        HotstringRepository.upsert(this@OpenMoaIME, HotstringRule(existing?.id ?: HotstringRepository.newId(), trigger, expansion))
                        hideEditForm()
                        showClipboardPanel()
                    }
                }
            }
        }
        val cancelBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_cancel)
            setOnClickListener {
                hideEditForm()
                showClipboardPanel()
            }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(triggerEditText, lp)
        container.addView(expansionEditText, lp)
        container.addView(btnRow, lp)
        container.visibility = View.VISIBLE
        applyFormSkin(container, listOf(triggerEditText, expansionEditText), listOf(cancelBtn, saveBtn))
        val focusListener = android.view.View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) activeFormEditText = view as EditText
        }
        triggerEditText.onFocusChangeListener = focusListener
        expansionEditText.onFocusChangeListener = focusListener
        formUndoStack.clear()
        formRedoStack.clear()
        attachFormUndoWatcher(triggerEditText)
        attachFormUndoWatcher(expansionEditText)
        triggerEditText.requestFocus()
        activeFormEditText = triggerEditText
        refreshFormSuggestions(triggerEditText)
    }

    private fun showPhraseEditForm(key: PhraseKey) {
        if (!this::binding.isInitialized) return
        finishComposing()
        val container = binding.imeEditFormContainer
        container.removeAllViews()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val currentPhrase = key.getPhrase(this)
        val editText = EditText(this).apply {
            setText(currentPhrase)
            setSelection(currentPhrase.length)
            setSingleLine()
            hint = when (key) {
                is QuickPhraseKey -> getString(R.string.settings_quick_phrase_edit_hint)
                else -> getString(R.string.number_long_key_edit_hint)
            }
        }
        val saveBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_save)
            setOnClickListener {
                val raw = editText.text.toString()
                val phrase = if (key is UserCharKey) {
                    if (raw.isEmpty()) key.defaultPhrase
                    else raw.substring(0, raw.offsetByCodePoints(0, 1))
                } else {
                    raw.ifEmpty { key.defaultPhrase }
                }
                key.setPhrase(this@OpenMoaIME, phrase)
                when (key) {
                    is QuickPhraseKey -> (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.refreshQuickPhraseBadges()
                    is UserCharKey -> (keyboardViews[IMEMode.IME_KO] as? OpenMoaView)?.refreshUserCharLabels()
                    else -> Unit
                }
                hideEditForm()
            }
        }
        val cancelBtn = Button(this).apply {
            text = getString(R.string.clipboard_edit_cancel)
            setOnClickListener { hideEditForm() }
        }
        val resetBtn = Button(this).apply {
            text = getString(R.string.settings_quick_phrase_reset)
            setOnClickListener {
                editText.setText(key.defaultPhrase)
                editText.setSelection(key.defaultPhrase.length)
            }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(resetBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(editText, lp)
        container.addView(btnRow, lp)
        container.visibility = View.VISIBLE
        applyFormSkin(container, listOf(editText), listOf(resetBtn, cancelBtn, saveBtn))
        formUndoStack.clear()
        formRedoStack.clear()
        attachFormUndoWatcher(editText)
        editText.requestFocus()
        activeFormEditText = editText
        refreshFormSuggestions(editText)
    }

    private fun refreshFormSuggestions(editText: EditText) {
        val cursor = editText.selectionStart.coerceAtLeast(0)
        val textBefore = editText.text.substring(0, cursor)
        val wordStart = textBefore.indexOfLast { it == ' ' || it == '\n' } + 1
        val word = textBefore.substring(wordStart)
        if (word.isNotEmpty()) refreshSuggestions(word) else deactivateSuggestionBar()
    }

    private fun handleFormKey(key: Any, editText: EditText) {
        val cursor = editText.selectionStart.coerceAtLeast(0)
        val selEnd = editText.selectionEnd.coerceAtLeast(cursor)
        when (key) {
            is SpecialKey -> when (key) {
                SpecialKey.BACKSPACE -> {
                    if (cursor < selEnd) {
                        hangulAssembler.clear()
                        editText.text.delete(cursor, selEnd)
                        return
                    }
                    val unresolved = hangulAssembler.getUnresolved()
                    if (unresolved != null) {
                        hangulAssembler.removeLastJamo(isQwertyKoActive())
                        val newUnresolved = hangulAssembler.getUnresolved()
                        val start = (cursor - unresolved.length).coerceAtLeast(0)
                        editText.text.replace(start, cursor, newUnresolved ?: "")
                    } else {
                        if (cursor > 0) editText.text.delete(cursor - 1, cursor)
                    }
                }
                SpecialKey.DELETE -> {
                    hangulAssembler.clear()
                    if (cursor < selEnd) {
                        editText.text.delete(cursor, selEnd)
                    } else if (cursor < editText.text.length) {
                        editText.text.delete(cursor, cursor + 1)
                    }
                }
                SpecialKey.ENTER -> {
                    hangulAssembler.clear()
                    if (editText.maxLines > 1) {
                        editText.text.replace(cursor, selEnd, "\n")
                    }
                }
                else -> hangulAssembler.clear()
            }
            is String -> {
                when {
                    key.matches(HangulAssembler.JAMO_REGEX) -> {
                        val actualCursor: Int
                        if (cursor < selEnd) {
                            hangulAssembler.clear()
                            editText.text.delete(cursor, selEnd)
                            actualCursor = editText.selectionStart.coerceAtLeast(0)
                        } else {
                            actualCursor = cursor
                        }
                        val unresolved = hangulAssembler.getUnresolved()
                        val replaceStart = if (unresolved != null) {
                            (actualCursor - unresolved.length).coerceAtLeast(0)
                        } else {
                            actualCursor
                        }
                        val resolved = hangulAssembler.appendJamo(key)
                        val newUnresolved = hangulAssembler.getUnresolved() ?: ""
                        editText.text.replace(replaceStart, actualCursor, (resolved ?: "") + newUnresolved)
                    }
                    else -> {
                        hangulAssembler.clear()
                        editText.text.replace(cursor, selEnd, key)
                    }
                }
            }
        }
        refreshFormSuggestions(editText)
    }

    private fun applyFormSkin(container: LinearLayout, editTexts: List<EditText>, buttons: List<Button>) {
        val skin = SettingsPreferences.getKeyboardSkin(this)
        val fgColor = SkinApplier.fgColor(this, skin)
        val mutedColor = ContextCompat.getColor(this, skin.keyFgMutedColorRes)
        val bgColor = SkinApplier.keyboardBgColor(this, skin)
        val density = resources.displayMetrics.density
        val dp2 = (2 * density).toInt()
        val dp4 = (4 * density).toInt()
        val dp8 = (8 * density).toInt()
        val btnHeight = (36 * density).toInt()
        container.setBackgroundColor(bgColor)
        for (et in editTexts) {
            et.setTextColor(fgColor)
            et.setHintTextColor(mutedColor)
            et.background = SkinApplier.buildKeyDrawable(this, skin, pressed = false)
            et.setPadding(dp8, dp4, dp8, dp4)
            (et.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(dp2, dp2, dp2, dp2)
                et.layoutParams = lp
            }
        }
        for (btn in buttons) {
            btn.setTextColor(fgColor)
            btn.background = SkinApplier.buildKeySelector(this, skin)
            btn.textSize = 14f
            btn.minHeight = 0
            btn.minimumHeight = 0
            btn.setPadding(dp4, 0, dp4, 0)
            btn.layoutParams?.let { lp ->
                lp.height = btnHeight
                btn.layoutParams = lp
            }
        }
    }

    private fun attachFormUndoWatcher(editText: EditText) {
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!isFormUndoRedo) {
                    pendingUndoState = TextState(s.toString(), editText.selectionStart.coerceAtLeast(0))
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!isFormUndoRedo) {
                    pendingUndoState?.let {
                        formUndoStack.addLast(it)
                        formRedoStack.clear()
                    }
                    pendingUndoState = null
                }
            }
        })
    }

    private fun hideEditForm() {
        if (!this::binding.isInitialized) return
        activeFormEditText = null
        formUndoStack.clear()
        formRedoStack.clear()
        hangulAssembler.clear()
        composingText = ""
        binding.imeEditFormContainer.apply {
            visibility = View.GONE
            removeAllViews()
        }
    }

    override fun onDestroy() {
        stopInputBindingPoll()
        if (this::broadcastReceiver.isInitialized) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
        }
        hardwareKeyboardController.onDestroy()
        userWordStore.flush()
        koreanUserWordStore.flush()
        feedbackPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (this::keyboardViews.isInitialized) {
            keyboardViews = buildKeyboardViews()
            wireKoViewCallbacks()
            if (this::binding.isInitialized) {
                applyKeyboardLayout()
                setKeyboard(imeMode)
            }
        }
        if (this::binding.isInitialized) {
            applyGestureNavigationSpacing(binding.root)
        }
        hardwareKeyboardController.onConfigurationChanged()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKeyboardController.onKeyDown(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKeyboardController.onKeyUp(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (restarting) return
        if (isRealInputContext(attribute)) {
            hardwareKeyboardController.onInputStarted()
            startInputBindingPoll()
        } else {
            hardwareKeyboardController.onInputFinished()
            stopInputBindingPoll()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        hardwareKeyboardController.onInputFinished()
        stopInputBindingPoll()
    }

    override fun onUnbindInput() {
        super.onUnbindInput()
        hardwareKeyboardController.onInputFinished()
        stopInputBindingPoll()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        hardwareKeyboardController.onWindowShown()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        hardwareKeyboardController.onWindowHidden()
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val styleBuilder = UiVersions.newStylesBuilder()
        val foregroundColor = ContextCompat.getColor(this@OpenMoaIME, R.color.key_foreground)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackground(
                        Icon.createWithResource(this, R.drawable.selector_key_background)
                    )
                    .setPadding(toDp(4), toDp(4), toDp(4), toDp(4))
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(
                        Icon.createWithResource(this, R.drawable.selector_key_background)
                    )
                    .setPadding(toDp(4), toDp(4), toDp(4), toDp(4))
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setPadding(0, 0, 0, 0)
                    .setLayoutMargin(toDp(4), 0, toDp(4), 0)
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setPadding(0, 0, 0, 0)
                    .setLayoutMargin(toDp(4), 0, toDp(4), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(foregroundColor)
                    .setPadding(0, 0, 0, 0)
                    .setLayoutMargin(toDp(4), 0, toDp(4), 0)
                    .setTextSize(14F)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(
                        Color.argb(
                            127,
                            Color.red(foregroundColor),
                            Color.green(foregroundColor),
                            Color.blue(foregroundColor),
                        )
                    )
                    .setPadding(0, 0, 0, 0)
                    .setLayoutMargin(toDp(4), 0, toDp(4), 0)
                    .setTextSize(14F)
                    .build()
            )
            .build()
        styleBuilder.addStyle(style)
        val styleBundle = styleBuilder.build()
        // According to the document, when this list has only one element,
        // the first element should be used repeatedly. But it doesn't work that way on 1Password.
        // So I decide on the size of this list as the number of suggestions.
        // https://developer.android.com/reference/android/view/inputmethod/InlineSuggestionsRequest.Builder#public-constructors_1
        val presentationSpecs = List(config.maxSuggestionCount) {
            InlinePresentationSpec.Builder(
                Size(toDp(32), getHeight()), Size(toDp(640), getHeight())
            ).setStyle(styleBundle).build()
        }
        return InlineSuggestionsRequest.Builder(presentationSpecs)
            .setMaxSuggestionCount(config.maxSuggestionCount)
            .build()
    }

    private fun applyKeyboardLayout() {
        val skin = SettingsPreferences.getKeyboardSkin(this)
        val bgColor = SkinApplier.keyboardBgColor(this, skin)
        binding.root.setBackgroundColor(bgColor)
        binding.keyboardContainer.setBackgroundColor(bgColor)
        window.window?.apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                navigationBarColor = bgColor
            }
            val isLightBg = Color.luminance(bgColor) > 0.5f
            insetsController?.setSystemBarsAppearance(
                if (isLightBg) APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val oneHandMode = if (isLandscape) OneHandMode.NONE else SettingsPreferences.getOneHandMode(this)
        val displayWidth = resources.displayMetrics.widthPixels
        val keyboardWidth = if (oneHandMode.isReduced) {
            (displayWidth * 0.80f).toInt()
        } else {
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        }
        val keyboardHeight = calculateKeyboardHeight()
        val keyboardGravity = oneHandMode.gravity
        val params = android.widget.FrameLayout.LayoutParams(keyboardWidth, keyboardHeight).apply {
            gravity = keyboardGravity
        }
        binding.keyboardFrameLayout.layoutParams = params
        binding.clipboardPanel.layoutParams = android.widget.FrameLayout.LayoutParams(keyboardWidth, keyboardHeight).apply {
            gravity = keyboardGravity
        }
        val dp4 = (4 * resources.displayMetrics.density).toInt()
        binding.imeEditFormContainer.layoutParams = LinearLayout.LayoutParams(
            keyboardWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = keyboardGravity
            bottomMargin = dp4
        }
        (binding.wordSuggestionBar.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = keyboardWidth
            lp.gravity = keyboardGravity
            binding.wordSuggestionBar.layoutParams = lp
        }
        if (this::binding.isInitialized) {
            val fg = SkinApplier.fgColor(this, skin)
            val bg = SkinApplier.keyboardBgColor(this, skin)
            val keyBg = SkinApplier.keyBgColor(this, skin)
            binding.wordSuggestionBar.applyColors(fg, bg, keyBg)
            binding.clipboardPanel.applyColors(fg, bg)
            binding.wordSuggestionBar.visibility = View.VISIBLE
        }
    }

    private fun calculateKeyboardHeight(): Int {
        val displayHeight = resources.displayMetrics.heightPixels
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            return (displayHeight * 0.50f).toInt()
        }
        val heightScale = SettingsPreferences.getKeypadHeight(this).heightScale
        return (displayHeight * 0.35f * heightScale).toInt()
    }

    private fun getHeight(): Int {
        return toDp(32)
    }

    private fun toDp(pixel: Int): Int {
        return (pixel * resources.displayMetrics.density).roundToInt()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!this::binding.isInitialized) {
            return false
        }
        binding.suggestionStripStartChipGroup.removeAllViews()
        binding.suggestionStripScrollableChipGroup.removeAllViews()
        binding.suggestionStripEndChipGroup.removeAllViews()
        binding.suggestionStripLayout.visibility =
            if (response.inlineSuggestions.isEmpty()) View.GONE else View.VISIBLE
        response.inlineSuggestions.forEach { inlineSuggestion ->
            val size = Size(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            inlineSuggestion.inflate(this, size, mainExecutor) { view ->
                if (inlineSuggestion.info.isPinned) {
                    if (binding.suggestionStripStartChipGroup.isEmpty()) {
                        binding.suggestionStripStartChipGroup.addView(view)
                    } else {
                        binding.suggestionStripEndChipGroup.addView(view)
                    }
                } else {
                    binding.suggestionStripScrollableChipGroup.addView(view)
                }
            }
        }
        return true
    }

    private fun isSimpleQwertyKoActive(): Boolean {
        val v = if (this::keyboardViews.isInitialized) keyboardViews[IMEMode.IME_KO] else null
        return (v as? QuertyKoView)?.simple == true
    }

    private fun applyGestureNavigationSpacing(rootView: View) {
        rootView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowInsets = rootView.rootWindowInsets
                val gestureInsets = windowInsets?.getInsets(WindowInsets.Type.systemGestures())
                val isGestureNavigation = (gestureInsets?.left ?: 0) > 0 || (gestureInsets?.right ?: 0) > 0

                val paddingBottom = if (isGestureNavigation) {
                    (60 * resources.displayMetrics.density).toInt()
                } else {
                    0
                }
                rootView.setPadding(
                    rootView.paddingLeft,
                    rootView.paddingTop,
                    rootView.paddingRight,
                    paddingBottom
                )
            }
        }
    }

    companion object {
        const val INTENT_ACTION = "keyInput"
        const val EXTRA_NAME = "key"
        private const val INPUT_POLL_INTERVAL_MS = 3000L

        private const val SIMPLE_MULTI_TAP_MS = 400L

        private val WORD_COMMIT_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")

        // 단모음 multi-tap 합성: 같은 자모 두 번 → 합성 자모
        private val SIMPLE_MULTI_TAP_MAP = mapOf(
            "ㅏ" to "ㅑ", "ㅓ" to "ㅕ", "ㅗ" to "ㅛ", "ㅜ" to "ㅠ",
            "ㅐ" to "ㅒ", "ㅔ" to "ㅖ",
            "ㄱ" to "ㄲ", "ㄷ" to "ㄸ", "ㅂ" to "ㅃ",
            "ㅅ" to "ㅆ", "ㅈ" to "ㅉ",
        )
    }

}