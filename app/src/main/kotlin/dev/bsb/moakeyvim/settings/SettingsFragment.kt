package dev.bsb.moakeyvim.settings

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import dev.bsb.moakeyvim.R
import dev.bsb.moakeyvim.clipboard.ClipboardEntry
import dev.bsb.moakeyvim.clipboard.ClipboardRepository
import dev.bsb.moakeyvim.hotstring.HotstringRule
import dev.bsb.moakeyvim.hotstring.HotstringRepository
import dev.bsb.moakeyvim.config.EnterLongPressAction
import dev.bsb.moakeyvim.config.HangulInputMode
import dev.bsb.moakeyvim.config.LandscapeKoLayout
import dev.bsb.moakeyvim.config.HapticStrength
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.config.KeypadHeight
import dev.bsb.moakeyvim.config.LongPressTime
import dev.bsb.moakeyvim.config.OneHandMode
import dev.bsb.moakeyvim.config.SoundType
import dev.bsb.moakeyvim.config.SoundVolume
import dev.bsb.moakeyvim.config.SpaceLongPressAction
import dev.bsb.moakeyvim.quickphrase.NumberLongKey
import dev.bsb.moakeyvim.quickphrase.QuickPhraseKey
import dev.bsb.moakeyvim.quickphrase.QwertyLongKey
import dev.bsb.moakeyvim.suggestion.UserWordStore
import org.koin.android.ext.android.get
import org.koin.core.qualifier.named

class SettingsFragment : PreferenceFragmentCompat() {

    companion object {
        private const val SETTINGS_FILE_NAME = "openmoa_settings.json"
        private const val DEV_TAP_REQUIRED = 7

        private val INT_KEYS = setOf(
            SettingsPreferences.KEY_FLOATING_INDICATOR_X,
            SettingsPreferences.KEY_FLOATING_INDICATOR_Y,
        )

        private val BOOLEAN_KEYS = setOf(
            SettingsPreferences.KEY_KEY_PREVIEW,
            SettingsPreferences.KEY_AUTO_SPACE_PERIOD,
            SettingsPreferences.KEY_AUTO_CAPITALIZE_ENGLISH,
            SettingsPreferences.KEY_HOTSTRING_ENABLED,
            SettingsPreferences.KEY_WORD_SUGGESTION_ENABLED,
            SettingsPreferences.KEY_KOREAN_WORD_SUGGESTION_ENABLED,
            SettingsPreferences.KEY_CLIPBOARD_ENABLED,
            SettingsPreferences.KEY_FLOATING_INDICATOR_ENABLED,
            SettingsPreferences.KEY_OVERLAY_PERMISSION_NOTIFIED,
            SettingsPreferences.KEY_HW_CAPSLOCK_TO_CTRL,
            SettingsPreferences.KEY_HW_TAB_VIM_MODE,
            SettingsPreferences.KEY_ENGLISH_KEYBOARD_ENABLED,
        )

        private val ALLOWED_KEYS: Set<String> by lazy {
            buildSet {
                addAll(SettingsPreferences.ALL_KEYS)
                QuickPhraseKey.values().forEach { add(it.prefKey) }
                QwertyLongKey.values().forEach { add(it.prefKey) }
                NumberLongKey.values().forEach { add(it.prefKey) }
            }
        }
    }

    private var devTapCount = 0
    private var devTapLastTime = 0L

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SettingsPreferences.KEY_CLIPBOARD_ENABLED -> updateClipboardDependents()
            SettingsPreferences.KEY_WORD_SUGGESTION_ENABLED,
            SettingsPreferences.KEY_KOREAN_WORD_SUGGESTION_ENABLED -> updateSuggestionDependents()
            SettingsPreferences.KEY_HW_TAB_VIM_MODE -> updateVimDependents()
            SettingsPreferences.KEY_ENGLISH_KEYBOARD_ENABLED -> updateEnglishDependents()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importSettings(uri) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = SettingsPreferences.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences_main, rootKey)
        setupListPreferences()
        setupClickPreferences()
        setupVersionPreference()
        updateClipboardDependents()
        updateSuggestionDependents()
        updateVimDependents()
        updateEnglishDependents()
    }

    override fun onResume() {
        super.onResume()
        updateGestureAngleSummary()
        updateOverlayPermissionSummary()
        preferenceManager.sharedPreferences
            ?.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    override fun onPause() {
        super.onPause()
        preferenceManager.sharedPreferences
            ?.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun setupListPreferences() {
        setupClipboardListPreferences()
        pref<ListPreference>(SettingsPreferences.KEY_HANGUL_INPUT_MODE)
            ?.setupEnum(HangulInputMode.values(), { it.labelResId }, HangulInputMode.TWO_HAND_MOAKEY)
        pref<ListPreference>(SettingsPreferences.KEY_LANDSCAPE_KO_LAYOUT)
            ?.setupEnum(LandscapeKoLayout.values(), { it.labelResId }, LandscapeKoLayout.NONE)
        pref<ListPreference>(SettingsPreferences.KEY_KEYBOARD_SKIN)
            ?.setupEnum(KeyboardSkin.values(), { it.labelResId }, KeyboardSkin.WHITE)
        pref<ListPreference>(SettingsPreferences.KEY_KEYPAD_HEIGHT)
            ?.setupEnum(KeypadHeight.values(), { it.labelResId }, KeypadHeight.LOW)
        pref<ListPreference>(SettingsPreferences.KEY_ONE_HAND_MODE)
            ?.setupEnum(OneHandMode.values(), { it.labelResId }, OneHandMode.NONE)
        pref<ListPreference>(SettingsPreferences.KEY_LONG_PRESS_TIME)
            ?.setupEnum(LongPressTime.values(), { it.labelResId }, LongPressTime.MS_500)
        pref<ListPreference>(SettingsPreferences.KEY_SPACE_LONG_PRESS_ACTION)
            ?.setupEnum(SpaceLongPressAction.values(), { it.labelResId }, SpaceLongPressAction.IME_PICKER)
        pref<ListPreference>(SettingsPreferences.KEY_ENTER_LONG_PRESS_ACTION)
            ?.setupEnum(EnterLongPressAction.values(), { it.labelResId }, EnterLongPressAction.ARROW)
        pref<ListPreference>(SettingsPreferences.KEY_HAPTIC_STRENGTH)
            ?.setupEnum(HapticStrength.values(), { it.labelResId }, HapticStrength.MEDIUM)
        pref<ListPreference>(SettingsPreferences.KEY_SOUND_VOLUME)
            ?.setupEnum(SoundVolume.values(), { it.labelResId }, SoundVolume.OFF)
        pref<ListPreference>(SettingsPreferences.KEY_SOUND_TYPE)
            ?.setupEnum(SoundType.values(), { it.labelResId }, SoundType.STANDARD)
        pref<ListPreference>(SettingsPreferences.KEY_MIN_LEARN_COUNT)?.apply {
            entries = arrayOf("1", "2", "3", "5")
            entryValues = arrayOf("1", "2", "3", "5")
            if (value == null) value = "2"
        }
    }

    private fun setupClipboardListPreferences() {
        val ctx = requireContext()
        pref<ListPreference>(SettingsPreferences.KEY_CLIPBOARD_MAX_ITEMS)?.apply {
            entries = arrayOf("20", "50", "100", "200")
            entryValues = arrayOf("20", "50", "100", "200")
            if (value == null) value = "50"
        }
        pref<ListPreference>(SettingsPreferences.KEY_CLIPBOARD_EXPIRY_MINUTES)?.apply {
            entries = arrayOf(
                ctx.getString(R.string.settings_clipboard_expiry_6h),
                ctx.getString(R.string.settings_clipboard_expiry_1d),
                ctx.getString(R.string.settings_clipboard_expiry_1w),
                ctx.getString(R.string.settings_clipboard_expiry_1m),
                ctx.getString(R.string.settings_clipboard_expiry_unlimited),
            )
            entryValues = arrayOf("360", "1440", "10080", "43200", "0")
            if (value == null) value = "10080"
        }
    }

    private fun setupClickPreferences() {
        pref<Preference>("pref_gesture_angle")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), GestureAngleActivity::class.java))
            true
        }
        pref<Preference>("pref_shortcut")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ShortcutSettingsActivity::class.java))
            true
        }
        pref<Preference>("pref_hotstring")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), HotstringListActivity::class.java))
            true
        }
        pref<Preference>("pref_learned_words")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LearnedWordsActivity::class.java))
            true
        }
        pref<Preference>("pref_data_export")?.setOnPreferenceClickListener {
            exportSettings()
            true
        }
        pref<Preference>("pref_data_import")?.setOnPreferenceClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
            true
        }
        pref<Preference>("pref_data_reset")?.setOnPreferenceClickListener {
            showResetConfirmDialog()
            true
        }
        pref<Preference>("pref_overlay_permission")?.setOnPreferenceClickListener {
            launchOverlayPermissionSettings()
            true
        }
        pref<Preference>("pref_vim_keymap")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), VimKeymapActivity::class.java))
            true
        }
    }

    private fun launchOverlayPermissionSettings() {
        val ctx = requireContext()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun updateOverlayPermissionSummary() {
        val ctx = requireContext()
        val granted = Settings.canDrawOverlays(ctx)
        pref<Preference>("pref_overlay_permission")?.setSummary(
            if (granted) R.string.settings_overlay_permission_summary_granted
            else R.string.settings_overlay_permission_summary_denied
        )
    }

    private fun setupVersionPreference() {
        val ctx = requireContext()
        val versionName = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        pref<Preference>("pref_version")?.apply {
            summary = versionName
            setOnPreferenceClickListener {
                val now = System.currentTimeMillis()
                if (now - devTapLastTime > 3000) devTapCount = 0
                devTapLastTime = now
                devTapCount++
                val remaining = DEV_TAP_REQUIRED - devTapCount
                when {
                    remaining <= 0 -> {
                        devTapCount = 0
                        startActivity(Intent(ctx, DevToolsActivity::class.java))
                    }
                    remaining <= 3 -> {
                        Toast.makeText(ctx, "${remaining}번 더 누르면 개발자 모드", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }
    }

    private fun updateClipboardDependents() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val enabled = prefs.getBoolean(SettingsPreferences.KEY_CLIPBOARD_ENABLED, true)
        pref<Preference>(SettingsPreferences.KEY_CLIPBOARD_MAX_ITEMS)?.isEnabled = enabled
        pref<Preference>(SettingsPreferences.KEY_CLIPBOARD_EXPIRY_MINUTES)?.isEnabled = enabled
    }

    private fun updateSuggestionDependents() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val anyEnabled = prefs.getBoolean(SettingsPreferences.KEY_WORD_SUGGESTION_ENABLED, false)
            || prefs.getBoolean(SettingsPreferences.KEY_KOREAN_WORD_SUGGESTION_ENABLED, false)
        pref<Preference>(SettingsPreferences.KEY_MIN_LEARN_COUNT)?.isEnabled = anyEnabled
        pref<Preference>("pref_learned_words")?.isEnabled = anyEnabled
    }

    private fun updateVimDependents() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val enabled = prefs.getBoolean(SettingsPreferences.KEY_HW_TAB_VIM_MODE, false)
        pref<Preference>("pref_vim_keymap")?.isEnabled = enabled
    }

    private fun updateEnglishDependents() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val enabled = prefs.getBoolean(SettingsPreferences.KEY_ENGLISH_KEYBOARD_ENABLED, true)
        pref<Preference>(SettingsPreferences.KEY_AUTO_CAPITALIZE_ENGLISH)?.isEnabled = enabled
        pref<Preference>(SettingsPreferences.KEY_WORD_SUGGESTION_ENABLED)?.isEnabled = enabled
    }

    private fun updateGestureAngleSummary() {
        val ctx = requireContext()
        val preset = getString(SettingsPreferences.getGestureAnglePreset(ctx).labelResId)
        val threshold = SettingsPreferences.getGestureThreshold(ctx)
        pref<Preference>("pref_gesture_angle")?.summary = "$preset · ${threshold}dp"
    }

    private fun <T : Enum<T>> ListPreference.setupEnum(
        values: Array<T>,
        labelOf: (T) -> Int,
        default: T
    ) {
        val ctx = requireContext()
        entries = values.map { ctx.getString(labelOf(it)) }.toTypedArray()
        entryValues = values.map { it.name }.toTypedArray()
        if (value == null) value = default.name
    }

    private inline fun <reified T : Preference> pref(key: String): T? = findPreference(key)

    private fun exportSettings() {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val msgResId = try {
                val prefs = ctx.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                val settingsObj = JSONObject()
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> settingsObj.put(key, value)
                        is String -> settingsObj.put(key, value)
                        is Int -> settingsObj.put(key, value)
                        is Long -> settingsObj.put(key, value)
                        is Float -> settingsObj.put(key, value.toDouble())
                    }
                }

                val clipboardArray = JSONArray()
                ClipboardRepository.getAll(ctx).forEach { entry ->
                    clipboardArray.put(JSONObject().apply {
                        put("id", entry.id)
                        put("text", entry.text)
                        put("pinned", entry.pinned)
                        put("createdAt", entry.createdAt)
                    })
                }

                val hotstringsArray = JSONArray()
                HotstringRepository.getAll(ctx).forEach { rule ->
                    hotstringsArray.put(JSONObject().apply {
                        put("id", rule.id)
                        put("trigger", rule.trigger)
                        put("expansion", rule.expansion)
                        put("enabled", rule.enabled)
                    })
                }

                val koStore: UserWordStore = get(named("ko"))
                val enStore: UserWordStore = get(named("en"))
                val learnedKoObj = JSONObject().also { obj ->
                    koStore.entries().forEach { (word, count) -> obj.put(word, count) }
                }
                val learnedEnObj = JSONObject().also { obj ->
                    enStore.entries().forEach { (word, count) -> obj.put(word, count) }
                }
                val blacklistKoArr = JSONArray().also { arr ->
                    koStore.blacklist().forEach { arr.put(it) }
                }
                val blacklistEnArr = JSONArray().also { arr ->
                    enStore.blacklist().forEach { arr.put(it) }
                }

                val jsonText = JSONObject().apply {
                    put("version", 3)
                    put("exportedAt", System.currentTimeMillis())
                    put("settings", settingsObj)
                    put("clipboard", clipboardArray)
                    put("hotstrings", hotstringsArray)
                    put("learnedWords", JSONObject().apply {
                        put("ko", learnedKoObj)
                        put("en", learnedEnObj)
                    })
                    put("blacklist", JSONObject().apply {
                        put("ko", blacklistKoArr)
                        put("en", blacklistEnArr)
                    })
                }.toString(2)

                val resolver = ctx.contentResolver
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(SETTINGS_FILE_NAME),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        resolver.delete(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI.buildUpon()
                                .appendPath(id.toString()).build(),
                            null, null
                        )
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, SETTINGS_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore insert failed")
                (resolver.openOutputStream(uri) ?: throw Exception("openOutputStream failed"))
                    .use { it.write(jsonText.toByteArray()) }
                R.string.settings_data_export_success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SettingsFragment", "export failed", e)
                R.string.settings_data_export_fail
            }
            withContext(Dispatchers.Main) {
                if (isAdded) Toast.makeText(ctx, msgResId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importSettings(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val resolver = ctx.contentResolver
                val jsonText = resolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw Exception("openInputStream failed")
                val root = JSONObject(jsonText)
                val version = root.optInt("version")
                if (version != 2 && version != 3) throw Exception("Unsupported version")
                val settingsObj = root.optJSONObject("settings")
                    ?: throw Exception("Invalid format: missing settings")

                // 1단계: 전체 파싱 (실패 시 적용 없이 예외)
                val settingsEdits = mutableListOf<Pair<String, Any>>()
                settingsObj.keys().forEach { key ->
                    if (key !in ALLOWED_KEYS) return@forEach
                    when {
                        key in BOOLEAN_KEYS -> settingsEdits.add(key to settingsObj.optBoolean(key))
                        key in INT_KEYS -> settingsEdits.add(key to settingsObj.optInt(key))
                        else -> {
                            val value = settingsObj.optString(key)
                            if (value.isNotEmpty()) settingsEdits.add(key to value)
                        }
                    }
                }
                val newHotstrings = root.optJSONArray("hotstrings")?.let { array ->
                    List(array.length()) { i ->
                        val obj = array.getJSONObject(i)
                        HotstringRule(
                            id = obj.getString("id"),
                            trigger = obj.getString("trigger"),
                            expansion = obj.getString("expansion"),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    }
                }
                val newClipboard = root.optJSONArray("clipboard")?.let { array ->
                    List(array.length()) { i ->
                        val obj = array.getJSONObject(i)
                        ClipboardEntry(
                            id = obj.getString("id"),
                            text = obj.getString("text"),
                            pinned = obj.optBoolean("pinned", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    }
                }

                val learnedKo = if (version == 3) parseLearnedData(
                    root.optJSONObject("learnedWords")?.optJSONObject("ko"),
                    root.optJSONObject("blacklist")?.optJSONArray("ko")
                ) else null
                val learnedEn = if (version == 3) parseLearnedData(
                    root.optJSONObject("learnedWords")?.optJSONObject("en"),
                    root.optJSONObject("blacklist")?.optJSONArray("en")
                ) else null

                // 2단계: 일괄 적용
                val editor = ctx.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
                settingsEdits.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
                newHotstrings?.let { HotstringRepository.replaceAll(ctx, it) }
                newClipboard?.let { ClipboardRepository.replaceAll(ctx, it.filter { e -> e.text.isNotBlank() }) }
                learnedKo?.let { data ->
                    val store: UserWordStore = get(named("ko"))
                    store.clear()
                    store.importWords(data.words)
                    data.blacklist.forEach { store.addToBlacklist(it) }
                }
                learnedEn?.let { data ->
                    val store: UserWordStore = get(named("en"))
                    store.clear()
                    store.importWords(data.words)
                    data.blacklist.forEach { store.addToBlacklist(it) }
                }

                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SettingsFragment", "import failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val msgResId = if (success) R.string.settings_data_import_success else R.string.settings_data_import_fail
                Toast.makeText(ctx, msgResId, Toast.LENGTH_SHORT).show()
                if (success) {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.settingsContainer, SettingsFragment())
                        .commit()
                }
            }
        }
    }

    private fun showResetConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.settings_data_reset_confirm)
            .setPositiveButton(R.string.dialog_confirm) { _, _ -> resetAllSettings() }
            .setNegativeButton(R.string.settings_qwerty_long_key_cancel, null)
            .show()
    }

    private fun resetAllSettings() {
        val ctx = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            ctx.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            ClipboardRepository.clearAll(ctx)
            val koStore: UserWordStore = get(named("ko"))
            val enStore: UserWordStore = get(named("en"))
            koStore.clear()
            enStore.clear()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(ctx, R.string.settings_data_reset_success, Toast.LENGTH_SHORT).show()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.settingsContainer, SettingsFragment())
                    .commit()
            }
        }
    }
}

private data class LearnedData(
    val words: Map<String, Int>,
    val blacklist: Set<String>,
)

private fun parseLearnedData(wordsObj: JSONObject?, blacklistArr: JSONArray?): LearnedData {
    val words = mutableMapOf<String, Int>()
    wordsObj?.keys()?.forEach { key -> words[key] = wordsObj.getInt(key) }
    val bl = mutableSetOf<String>()
    if (blacklistArr != null) {
        for (i in 0 until blacklistArr.length()) bl.add(blacklistArr.getString(i))
    }
    return LearnedData(words, bl)
}
