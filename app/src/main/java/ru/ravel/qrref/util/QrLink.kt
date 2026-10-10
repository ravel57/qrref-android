package ru.ravel.qrref.util

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** Результат разбора содержимого отсканированного QR. */
data class ScannedQr(
	val key: String,
	val a: String,
	val b: String,
	val bridge: Boolean,
) {
	val hasPair: Boolean get() = a.isNotEmpty() && b.isNotEmpty()
}

/** Разбор и сборка ссылок вида `https://qrref.ru/?key=...` — повторяет логику Scanner.vue. */
object QrLink {

	private val KEY_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

	fun build(serverUrl: String, key: String): String = "${serverUrl.trimEnd('/')}/?key=$key"

	/** Возвращает null, если содержимое QR не похоже на URL. */
	fun parse(raw: String): ScannedQr? {
		val text = raw.trim()
		if (text.isEmpty()) return null
		val uri = parseUri(withScheme(text)) ?: return null

		val params = parseQuery(uri.rawQuery)
		fun param(name: String): String = params[name]?.trim().orEmpty().takeIf { KEY_REGEX.matches(it) } ?: ""

		val a = param("a")
		val b = param("b")
		val key = param("key").ifEmpty { param("k") }
		val bridge = (uri.path ?: "").lowercase().contains("/bridge") ||
				(a.isNotEmpty() && b.isNotEmpty()) ||
				params["bridge"] == "1"
		return ScannedQr(key = key, a = a, b = b, bridge = bridge)
	}

	/** Если весь текст — одна http(s)-ссылка, возвращает её, иначе null. */
	fun asWebUrl(text: String): String? {
		val trimmed = text.trim()
		if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
		if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
		val uri = parseUri(trimmed) ?: return null
		return trimmed.takeIf { !uri.host.isNullOrEmpty() }
	}

	/** Приводит введённый адрес сервера к виду `https://host[:port][/path]` без завершающего слэша. */
	fun normalizeServerUrl(input: String): String? {
		val trimmed = input.trim().trimEnd('/')
		if (trimmed.isEmpty()) return null
		val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
		val uri = parseUri(url) ?: return null
		val scheme = uri.scheme?.lowercase()
		if (scheme != "http" && scheme != "https") return null
		if (uri.host.isNullOrEmpty()) return null
		return url
	}

	private fun withScheme(text: String): String =
		if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) text
		else "https://$text"

	private fun parseUri(text: String): URI? = try {
		URI(text)
	} catch (_: URISyntaxException) {
		null
	}

	private fun parseQuery(query: String?): Map<String, String> {
		if (query.isNullOrEmpty()) return emptyMap()
		val result = LinkedHashMap<String, String>()
		for (pair in query.split('&')) {
			if (pair.isEmpty()) continue
			val idx = pair.indexOf('=')
			val name = decode(if (idx < 0) pair else pair.substring(0, idx))
			val value = if (idx < 0) "" else decode(pair.substring(idx + 1))
			result.putIfAbsent(name, value)
		}
		return result
	}

	private fun decode(s: String): String = try {
		URLDecoder.decode(s, "UTF-8")
	} catch (_: IllegalArgumentException) {
		s
	}
}
