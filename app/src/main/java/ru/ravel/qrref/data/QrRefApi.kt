package ru.ravel.qrref.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException

class ApiException(val code: Int) : IOException("HTTP $code")

data class PickedFile(
	val uri: Uri,
	val name: String,
	val size: Long,
	val mime: String?,
)

data class SavedFile(
	val name: String,
	val uri: Uri,
	val mime: String,
)

/** HTTP-ручки бэкенда qrRef (см. MainController). */
class QrRefApi(
	private val http: OkHttpClient,
	private val contentResolver: () -> ContentResolver,
	private val baseUrl: () -> String,
) {

	private val resolver: ContentResolver get() = contentResolver()

	suspend fun sendText(key: String, text: String) {
		val body = JSONObject().put("text", text).toString().toRequestBody(JSON)
		post(url("text", key), body)
	}

	suspend fun sendFile(key: String, file: PickedFile) {
		val type = file.mime?.toMediaTypeOrNull()
		val body = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", file.name, UriRequestBody(resolver, file.uri, type, file.size))
			.build()
		post(url("file", key), body)
	}

	/** Создаёт мост между двумя ключами и возвращает roomId из ответа сервера. */
	suspend fun createBridge(a: String, b: String): String? {
		val body = JSONObject().put("a", a).put("b", b).toString().toRequestBody(JSON)
		return postForJson(url("bridge"), body).optString("room").trim().ifEmpty { null }
	}

	/** roomId, если ключ уже сопряжён, иначе null. */
	suspend fun roomOf(key: String): String? = withContext(Dispatchers.IO) {
		http.newCall(Request.Builder().url(url("room", key)).build()).execute().use { response ->
			when {
				response.code == 404 -> null
				!response.isSuccessful -> throw ApiException(response.code)
				else -> JSONObject(response.body.string()).optString("room").trim().ifEmpty { null }
			}
		}
	}

	/** Отправляет файл собеседнику по мосту; собеседник получает сообщение с путём для скачивания. */
	suspend fun sendRoomFile(key: String, file: PickedFile) {
		val type = file.mime?.toMediaTypeOrNull()
		val body = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("file", file.name, UriRequestBody(resolver, file.uri, type, file.size))
			.build()
		post(url("room", "file", key), body)
	}

	/** Завершает чат: сервер расформировывает комнату и сообщает об этом обоим участникам. */
	suspend fun leaveRoom(key: String) {
		post(url("room", "leave", key), ByteArray(0).toRequestBody(null))
	}

	suspend fun sendRoomText(key: String, text: String) {
		val body = JSONObject().put("text", text).toString().toRequestBody(JSON)
		post(url("room", "text", key), body)
	}

	/** Скачивает файл по пути вида `file/<secret>` и сохраняет его в «Загрузки». */
	suspend fun download(path: String): SavedFile = withContext(Dispatchers.IO) {
		val target = url(*path.split('/').filter { it.isNotEmpty() }.toTypedArray())
		http.newCall(Request.Builder().url(target).build()).execute().use { response ->
			if (!response.isSuccessful) throw ApiException(response.code)
			val name = fileNameFrom(response.header("Content-Disposition"))
			val mime = mimeOf(name)
			saveToDownloads(name, mime, response)
		}
	}

	private fun url(vararg segments: String): HttpUrl {
		val builder = baseUrl().toHttpUrl().newBuilder()
		segments.forEach { builder.addPathSegment(it) }
		return builder.build()
	}

	private suspend fun post(url: HttpUrl, body: RequestBody) = withContext(Dispatchers.IO) {
		http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
			if (!response.isSuccessful) throw ApiException(response.code)
		}
	}

	private suspend fun postForJson(url: HttpUrl, body: RequestBody): JSONObject = withContext(Dispatchers.IO) {
		http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
			if (!response.isSuccessful) throw ApiException(response.code)
			try {
				JSONObject(response.body.string())
			} catch (_: Exception) {
				JSONObject()
			}
		}
	}

	private fun saveToDownloads(name: String, mime: String, response: Response): SavedFile {
		val values = ContentValues().apply {
			put(MediaStore.Downloads.DISPLAY_NAME, name)
			put(MediaStore.Downloads.MIME_TYPE, mime)
			put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
			put(MediaStore.Downloads.IS_PENDING, 1)
		}
		val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
			?: throw IOException("Cannot create file in Downloads")
		try {
			val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
			out.use { sink -> response.body.byteStream().use { it.copyTo(sink) } }
			resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
		} catch (e: Exception) {
			resolver.delete(uri, null, null)
			throw e
		}
		return SavedFile(name, uri, mime)
	}

	private class UriRequestBody(
		private val resolver: ContentResolver,
		private val uri: Uri,
		private val type: MediaType?,
		private val length: Long,
	) : RequestBody() {
		override fun contentType() = type

		override fun contentLength() = if (length > 0) length else -1L

		override fun writeTo(sink: BufferedSink) {
			val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
			input.source().use { sink.writeAll(it) }
		}
	}

	companion object {
		private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
		private val FILE_NAME = Regex("""filename=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
		private val FILE_NAME_STAR = Regex("""filename\*=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)

		/** Предпочитает `filename*=UTF-8''...` (с кириллицей), иначе берёт обычный `filename=`. */
		internal fun fileNameFrom(contentDisposition: String?): String {
			val encoded = contentDisposition?.let { FILE_NAME_STAR.find(it)?.groupValues?.get(1) }?.trim()
			val decoded = encoded?.let { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
			val raw = decoded ?: contentDisposition?.let { FILE_NAME.find(it)?.groupValues?.get(1) }?.trim()
			val safe = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != ".." }
			return safe ?: "qrref-file"
		}

		private fun mimeOf(name: String): String {
			val ext = name.substringAfterLast('.', "").lowercase()
			return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
		}
	}
}
