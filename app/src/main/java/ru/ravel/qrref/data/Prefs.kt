package ru.ravel.qrref.data

import android.content.Context
import androidx.core.content.edit
import ru.ravel.qrref.util.KeyGenerator

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Локальные настройки; аналог localStorage на сайте. */
class Prefs(context: Context) {

	private val sp = context.getSharedPreferences("qrref", Context.MODE_PRIVATE)

	var serverUrl: String
		get() = sp.getString(KEY_SERVER, null) ?: DEFAULT_SERVER
		set(value) = sp.edit { putString(KEY_SERVER, value) }

	/** Ключ этого устройства: по нему другие устройства присылают сюда данные. */
	var key: String
		get() = sp.getString(KEY_KEY, null) ?: KeyGenerator.generate().also { key = it }
		set(value) = sp.edit { putString(KEY_KEY, value) }

	var autoOpenUrl: Boolean
		get() = sp.getBoolean(KEY_AUTO_OPEN, true)
		set(value) = sp.edit { putBoolean(KEY_AUTO_OPEN, value) }

	var themeMode: ThemeMode
		get() = sp.getString(KEY_THEME, null)
			?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
			?: ThemeMode.SYSTEM
		set(value) = sp.edit { putString(KEY_THEME, value.name) }

	/** Ключ первой сессии при сопряжении (bridge_a в localStorage). */
	var bridgeA: String
		get() = sp.getString(KEY_BRIDGE_A, null) ?: ""
		set(value) = sp.edit { putString(KEY_BRIDGE_A, value) }

	companion object {
		const val DEFAULT_SERVER = "https://qrref.io"
		private const val KEY_SERVER = "server_url"
		private const val KEY_KEY = "key"
		private const val KEY_AUTO_OPEN = "auto_open_url"
		private const val KEY_THEME = "theme_mode"
		private const val KEY_BRIDGE_A = "bridge_a"
	}
}
