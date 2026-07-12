package dev.bsb.moakeyvim.settings

import android.content.Context
import dev.bsb.moakeyvim.config.EnterLongPressAction
import dev.bsb.moakeyvim.config.GestureAnglePreset
import dev.bsb.moakeyvim.config.GestureAngles
import dev.bsb.moakeyvim.config.HangulInputMode
import dev.bsb.moakeyvim.config.LandscapeKoLayout
import dev.bsb.moakeyvim.config.HapticStrength
import dev.bsb.moakeyvim.config.KeyboardSkin
import dev.bsb.moakeyvim.config.SoundType
import dev.bsb.moakeyvim.config.KeypadHeight
import dev.bsb.moakeyvim.config.LongPressTime
import dev.bsb.moakeyvim.config.OneHandMode
import dev.bsb.moakeyvim.config.SoundVolume
import dev.bsb.moakeyvim.config.SpaceLongPressAction
import dev.bsb.moakeyvim.hotstring.HotstringSortOrder
import dev.bsb.moakeyvim.settings.learnedwords.LearnedWordsSortOrder
import dev.bsb.moakeyvim.quickphrase.UserCharKey

object SettingsPreferences {

    const val PREFS_NAME = "openmoa_settings"
    const val KEY_HANGUL_INPUT_MODE = "hangul_input_mode"
    const val KEY_KEYPAD_HEIGHT = "keypad_height"
    const val KEY_ONE_HAND_MODE = "one_hand_mode"
    const val KEY_KEY_PREVIEW = "key_preview_enabled"
    const val KEY_LONG_PRESS_TIME = "long_press_time"
    const val KEY_SPACE_LONG_PRESS_ACTION = "space_long_press_action"
    const val KEY_AUTO_SPACE_PERIOD = "auto_space_period"
    const val KEY_KEYBOARD_SKIN = "keyboard_skin"
    const val KEY_AUTO_CAPITALIZE_ENGLISH = "auto_capitalize_english"
    const val KEY_ENTER_LONG_PRESS_ACTION = "enter_long_press_action"
    const val KEY_GESTURE_ANGLES = "gesture_angles"
    const val KEY_GESTURE_ANGLE_PRESET = "gesture_angle_preset"
    const val KEY_GESTURE_THRESHOLD = "gesture_threshold"
    const val KEY_HOTSTRING_ENABLED = "hotstring_enabled"
    const val KEY_HOTSTRING_SORT_ORDER = "hotstring_sort_order"
    const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    const val KEY_SOUND_VOLUME = "sound_volume"
    const val KEY_SOUND_TYPE = "sound_type"
    const val KEY_WORD_SUGGESTION_ENABLED = "word_suggestion_enabled"
    const val KEY_KOREAN_WORD_SUGGESTION_ENABLED = "korean_word_suggestion_enabled"
    const val KEY_CLIPBOARD_ENABLED = "clipboard_enabled"
    const val KEY_CLIPBOARD_MAX_ITEMS = "clipboard_max_items"
    const val KEY_CLIPBOARD_EXPIRY_MINUTES = "clipboard_expiry_minutes"
    const val KEY_MIN_LEARN_COUNT = "min_learn_count"
    const val KEY_LANDSCAPE_QWERTY = "landscape_qwerty"
    const val KEY_LANDSCAPE_KO_LAYOUT = "landscape_ko_layout"
    const val KEY_FLOATING_INDICATOR_ENABLED = "floating_indicator_enabled"
    const val KEY_FLOATING_INDICATOR_X = "floating_indicator_x"
    const val KEY_FLOATING_INDICATOR_Y = "floating_indicator_y"
    const val KEY_OVERLAY_PERMISSION_NOTIFIED = "overlay_permission_notified"
    const val KEY_HW_CAPSLOCK_TO_CTRL = "hw_capslock_to_ctrl"
    const val KEY_HW_TAB_VIM_MODE = "hw_tab_vim_mode"
    const val KEY_LEARNED_WORDS_SORT_ORDER = "learned_words_sort_order"
    const val KEY_ENGLISH_KEYBOARD_ENABLED = "english_keyboard_enabled"

    val ALL_KEYS = setOf(
        KEY_HANGUL_INPUT_MODE,
        KEY_KEYPAD_HEIGHT,
        KEY_ONE_HAND_MODE,
        KEY_KEY_PREVIEW,
        KEY_LONG_PRESS_TIME,
        KEY_SPACE_LONG_PRESS_ACTION,
        KEY_AUTO_SPACE_PERIOD,
        KEY_KEYBOARD_SKIN,
        KEY_AUTO_CAPITALIZE_ENGLISH,
        KEY_ENTER_LONG_PRESS_ACTION,
        KEY_GESTURE_ANGLES,
        KEY_GESTURE_ANGLE_PRESET,
        KEY_GESTURE_THRESHOLD,
        KEY_HOTSTRING_ENABLED,
        KEY_HOTSTRING_SORT_ORDER,
        KEY_HAPTIC_STRENGTH,
        KEY_SOUND_VOLUME,
        KEY_SOUND_TYPE,
        KEY_WORD_SUGGESTION_ENABLED,
        KEY_KOREAN_WORD_SUGGESTION_ENABLED,
        KEY_CLIPBOARD_ENABLED,
        KEY_CLIPBOARD_MAX_ITEMS,
        KEY_CLIPBOARD_EXPIRY_MINUTES,
        KEY_MIN_LEARN_COUNT,
        KEY_LANDSCAPE_QWERTY,
        KEY_LANDSCAPE_KO_LAYOUT,
        KEY_FLOATING_INDICATOR_ENABLED,
        KEY_FLOATING_INDICATOR_X,
        KEY_FLOATING_INDICATOR_Y,
        KEY_OVERLAY_PERMISSION_NOTIFIED,
        KEY_HW_CAPSLOCK_TO_CTRL,
        KEY_HW_TAB_VIM_MODE,
        KEY_ENGLISH_KEYBOARD_ENABLED,
    ) + UserCharKey.values().map { it.prefKey }.toSet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getEnglishKeyboardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENGLISH_KEYBOARD_ENABLED, true)

    fun setEnglishKeyboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENGLISH_KEYBOARD_ENABLED, enabled).apply()
    }

    fun getKeyPreviewEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEY_PREVIEW, true)

    fun setKeyPreviewEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEY_PREVIEW, enabled).apply()
    }

    fun getHangulInputMode(context: Context): HangulInputMode =
        HangulInputMode.fromString(prefs(context).getString(KEY_HANGUL_INPUT_MODE, null))

    fun getKeypadHeight(context: Context): KeypadHeight =
        KeypadHeight.fromString(prefs(context).getString(KEY_KEYPAD_HEIGHT, null))

    fun getLongPressTime(context: Context): LongPressTime =
        LongPressTime.fromString(prefs(context).getString(KEY_LONG_PRESS_TIME, null))

    fun getOneHandMode(context: Context): OneHandMode =
        OneHandMode.fromString(prefs(context).getString(KEY_ONE_HAND_MODE, null))

    fun getSpaceLongPressAction(context: Context): SpaceLongPressAction =
        SpaceLongPressAction.fromString(prefs(context).getString(KEY_SPACE_LONG_PRESS_ACTION, null))

    fun getKeyboardSkin(context: Context): KeyboardSkin =
        KeyboardSkin.fromString(prefs(context).getString(KEY_KEYBOARD_SKIN, null))

    fun getAutoSpacePeriod(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SPACE_PERIOD, false)

    fun setAutoSpacePeriod(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SPACE_PERIOD, enabled).apply()
    }

    fun getAutoCapitalizeEnglish(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CAPITALIZE_ENGLISH, true)

    fun setAutoCapitalizeEnglish(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CAPITALIZE_ENGLISH, enabled).apply()
    }

    fun getEnterLongPressAction(context: Context): EnterLongPressAction =
        EnterLongPressAction.fromString(prefs(context).getString(KEY_ENTER_LONG_PRESS_ACTION, null))

    fun getGestureAngles(context: Context): GestureAngles =
        GestureAngles.fromString(prefs(context).getString(KEY_GESTURE_ANGLES, null))

    fun setGestureAngles(context: Context, angles: GestureAngles) {
        prefs(context).edit().putString(KEY_GESTURE_ANGLES, angles.toPrefsString()).apply()
    }

    fun getGestureAnglePreset(context: Context): GestureAnglePreset =
        GestureAnglePreset.fromString(prefs(context).getString(KEY_GESTURE_ANGLE_PRESET, null))

    fun setGestureAnglePreset(context: Context, preset: GestureAnglePreset) {
        prefs(context).edit().putString(KEY_GESTURE_ANGLE_PRESET, preset.name).apply()
    }

    fun getGestureThreshold(context: Context): Int =
        prefs(context).getString(KEY_GESTURE_THRESHOLD, null)?.toIntOrNull() ?: 30

    fun setGestureThreshold(context: Context, value: Int) {
        prefs(context).edit().putString(KEY_GESTURE_THRESHOLD, value.toString()).apply()
    }

    fun getHotstringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HOTSTRING_ENABLED, false)

    fun setHotstringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HOTSTRING_ENABLED, enabled).apply()
    }

    fun getHotstringSortOrder(context: Context): HotstringSortOrder =
        HotstringSortOrder.fromString(prefs(context).getString(KEY_HOTSTRING_SORT_ORDER, null))

    fun setHotstringSortOrder(context: Context, order: HotstringSortOrder) {
        prefs(context).edit().putString(KEY_HOTSTRING_SORT_ORDER, order.name).apply()
    }

    fun getHapticStrength(context: Context): HapticStrength =
        HapticStrength.fromString(prefs(context).getString(KEY_HAPTIC_STRENGTH, null))

    fun getSoundVolume(context: Context): SoundVolume =
        SoundVolume.fromString(prefs(context).getString(KEY_SOUND_VOLUME, null))

    fun getSoundType(context: Context): SoundType =
        SoundType.fromString(prefs(context).getString(KEY_SOUND_TYPE, null))

    fun getWordSuggestionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WORD_SUGGESTION_ENABLED, false)

    fun setWordSuggestionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WORD_SUGGESTION_ENABLED, enabled).apply()
    }

    fun getKoreanWordSuggestionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KOREAN_WORD_SUGGESTION_ENABLED, false)

    fun setKoreanWordSuggestionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KOREAN_WORD_SUGGESTION_ENABLED, enabled).apply()
    }

    fun getClipboardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLIPBOARD_ENABLED, true)

    fun setClipboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLIPBOARD_ENABLED, enabled).apply()
    }

    fun getLandscapeKoLayout(context: Context): LandscapeKoLayout {
        val p = prefs(context)
        val saved = p.getString(KEY_LANDSCAPE_KO_LAYOUT, null)
        if (saved != null) return LandscapeKoLayout.fromString(saved)
        // 기존 boolean 설정 마이그레이션
        return if (p.getBoolean(KEY_LANDSCAPE_QWERTY, false)) {
            LandscapeKoLayout.QWERTY
        } else {
            LandscapeKoLayout.NONE
        }
    }

    fun getLandscapeQwerty(context: Context): Boolean = getLandscapeKoLayout(context).isQwerty

    @Deprecated("Use getLandscapeKoLayout() instead", ReplaceWith("getLandscapeKoLayout(context)"))
    fun setLandscapeQwerty(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LANDSCAPE_QWERTY, enabled).apply()
    }

    fun getMinLearnCount(context: Context): Int =
        prefs(context).getString(KEY_MIN_LEARN_COUNT, null)?.toIntOrNull() ?: 2

    fun getClipboardMaxItems(context: Context): Int =
        prefs(context).getString(KEY_CLIPBOARD_MAX_ITEMS, null)?.toIntOrNull() ?: 50

    fun getClipboardExpiryMinutes(context: Context): Int =
        prefs(context).getString(KEY_CLIPBOARD_EXPIRY_MINUTES, null)?.toIntOrNull() ?: 10080

    fun getFloatingIndicatorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_INDICATOR_ENABLED, true)

    fun setFloatingIndicatorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_INDICATOR_ENABLED, enabled).apply()
    }

    fun getFloatingIndicatorPosition(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains(KEY_FLOATING_INDICATOR_X) || !p.contains(KEY_FLOATING_INDICATOR_Y)) return null
        return p.getInt(KEY_FLOATING_INDICATOR_X, 0) to p.getInt(KEY_FLOATING_INDICATOR_Y, 0)
    }

    fun setFloatingIndicatorPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_FLOATING_INDICATOR_X, x)
            .putInt(KEY_FLOATING_INDICATOR_Y, y)
            .apply()
    }

    fun getOverlayPermissionNotified(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_PERMISSION_NOTIFIED, false)

    fun setOverlayPermissionNotified(context: Context, notified: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_PERMISSION_NOTIFIED, notified).apply()
    }

    fun getCapsLockToCtrl(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HW_CAPSLOCK_TO_CTRL, false)

    fun getTabVimMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HW_TAB_VIM_MODE, false)

    fun getLearnedWordsSortOrder(context: Context): LearnedWordsSortOrder =
        LearnedWordsSortOrder.fromString(prefs(context).getString(KEY_LEARNED_WORDS_SORT_ORDER, null))

    fun setLearnedWordsSortOrder(context: Context, order: LearnedWordsSortOrder) {
        prefs(context).edit().putString(KEY_LEARNED_WORDS_SORT_ORDER, order.name).apply()
    }

    fun save(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }
}
