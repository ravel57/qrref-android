package ru.ravel.qrref.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class StompFrame(
	val command: String,
	val headers: Map<String, String>,
	val body: String,
)

/** Кодирование/разбор текстовых STOMP-кадров (по одному кадру в сообщении WebSocket). */
object StompCodec {

	fun encode(command: String, headers: Map<String, String> = emptyMap(), body: String = ""): String {
		val sb = StringBuilder(command).append('\n')
		for ((name, value) in headers) {
			sb.append(escape(name)).append(':').append(escape(value)).append('\n')
		}
		return sb.append('\n').append(body).append('\u0000').toString()
	}

	/** Возвращает null для heart-beat (пустая строка). */
	fun decode(text: String): StompFrame? {
		val raw = text.trimStart('\n', '\r')
		if (raw.isEmpty()) return null
		val frame = raw.substring(0, raw.indexOf('\u0000').let { if (it < 0) raw.length else it })

		val lf = frame.indexOf("\n\n")
		val crlf = frame.indexOf("\r\n\r\n")
		val (headEnd, bodyStart) = when {
			lf >= 0 && (crlf < 0 || lf < crlf) -> lf to lf + 2
			crlf >= 0 -> crlf to crlf + 4
			else -> frame.length to frame.length
		}

		val lines = frame.substring(0, headEnd).split('\n').map { it.trimEnd('\r') }
		val headers = LinkedHashMap<String, String>()
		for (line in lines.drop(1)) {
			val idx = line.indexOf(':')
			if (idx < 0) continue
			headers.putIfAbsent(unescape(line.substring(0, idx)), unescape(line.substring(idx + 1)))
		}
		return StompFrame(lines[0], headers, frame.substring(bodyStart))
	}

	private fun escape(s: String): String = s
		.replace("\\", "\\\\")
		.replace("\r", "\\r")
		.replace("\n", "\\n")
		.replace(":", "\\c")

	private fun unescape(s: String): String {
		if (!s.contains('\\')) return s
		val sb = StringBuilder(s.length)
		var i = 0
		while (i < s.length) {
			val c = s[i]
			if (c == '\\' && i + 1 < s.length) {
				when (s[i + 1]) {
					'n' -> sb.append('\n')
					'r' -> sb.append('\r')
					'c' -> sb.append(':')
					'\\' -> sb.append('\\')
					else -> sb.append(c).append(s[i + 1])
				}
				i += 2
			} else {
				sb.append(c)
				i++
			}
		}
		return sb.toString()
	}
}

/**
 * Минимальный STOMP-клиент поверх «чистого» WebSocket-эндпоинта Spring SockJS (`/websocket/websocket`).
 * Сам переподключается и восстанавливает подписки после обрыва.
 */
class StompClient(
	private val http: OkHttpClient,
	private val scope: CoroutineScope,
) {

	private class Subscription(val id: String, var handler: (String) -> Unit)

	private inner class Connection : WebSocketListener() {
		@Volatile
		var socket: WebSocket? = null

		override fun onOpen(webSocket: WebSocket, response: Response) {
			socket = webSocket
			if (!isCurrent(this)) {
				webSocket.cancel()
				return
			}
			webSocket.send(
				StompCodec.encode("CONNECT", mapOf("accept-version" to "1.1,1.2", "heart-beat" to "0,0"))
			)
		}

		override fun onMessage(webSocket: WebSocket, text: String) {
			if (!isCurrent(this)) return
			val frame = StompCodec.decode(text) ?: return
			when (frame.command) {
				"CONNECTED" -> onStompConnected(webSocket)
				"MESSAGE" -> dispatch(frame)
				"ERROR" -> {
					Log.w(TAG, "STOMP error: ${frame.headers["message"]}")
					webSocket.cancel()
					onDisconnected(this)
				}
			}
		}

		override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
			webSocket.close(1000, null)
		}

		override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnected(this)

		override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
			Log.w(TAG, "WebSocket failure: ${t.message}")
			onDisconnected(this)
		}
	}

	private val lock = Any()
	private val subscriptions = LinkedHashMap<String, Subscription>()
	private var current: Connection? = null
	private var url: String? = null
	private var running = false
	private var ready = false
	private var attempt = 0
	private var nextId = 0
	private var reconnectJob: Job? = null

	private val _connected = MutableStateFlow(false)
	val connected: StateFlow<Boolean> = _connected

	fun start(wsUrl: String) {
		synchronized(lock) {
			if (running && url == wsUrl) return
			running = true
			url = wsUrl
			attempt = 0
		}
		open()
	}

	fun stop() {
		val old: Connection?
		synchronized(lock) {
			running = false
			reconnectJob?.cancel()
			reconnectJob = null
			old = current
			current = null
			ready = false
			url = null
		}
		_connected.value = false
		old?.socket?.close(1000, "bye")
	}

	fun subscribe(destination: String, handler: (String) -> Unit) {
		synchronized(lock) {
			val existing = subscriptions[destination]
			if (existing != null) {
				existing.handler = handler
				return
			}
			val sub = Subscription("sub-${nextId++}", handler)
			subscriptions[destination] = sub
			if (ready) sendSubscribe(destination, sub)
		}
	}

	fun unsubscribe(destination: String) {
		synchronized(lock) {
			val sub = subscriptions.remove(destination) ?: return
			if (ready) current?.socket?.send(StompCodec.encode("UNSUBSCRIBE", mapOf("id" to sub.id)))
		}
	}

	private fun open() {
		val connection = Connection()
		val target: String
		synchronized(lock) {
			if (!running) return
			target = url ?: return
			current?.socket?.cancel()
			current = connection
			ready = false
		}
		connection.socket = http.newWebSocket(Request.Builder().url(target).build(), connection)
	}

	private fun isCurrent(connection: Connection): Boolean = synchronized(lock) { current === connection }

	private fun onStompConnected(webSocket: WebSocket) {
		synchronized(lock) {
			ready = true
			attempt = 0
			subscriptions.forEach { (destination, sub) -> sendSubscribe(destination, sub, webSocket) }
		}
		_connected.value = true
	}

	private fun sendSubscribe(destination: String, sub: Subscription, socket: WebSocket? = current?.socket) {
		socket?.send(StompCodec.encode("SUBSCRIBE", mapOf("id" to sub.id, "destination" to destination)))
	}

	private fun dispatch(frame: StompFrame) {
		val handler = synchronized(lock) {
			subscriptions.values.firstOrNull { it.id == frame.headers["subscription"] }?.handler
		} ?: return
		try {
			handler(frame.body)
		} catch (e: Exception) {
			Log.e(TAG, "Subscription handler failed", e)
		}
	}

	private fun onDisconnected(connection: Connection) {
		val retryDelay: Long
		synchronized(lock) {
			if (current !== connection) return
			current = null
			ready = false
			if (!running) return
			retryDelay = minOf(30_000L, 1_000L shl minOf(attempt, 5))
			attempt++
			reconnectJob?.cancel()
			reconnectJob = scope.launch {
				delay(retryDelay)
				open()
			}
		}
		_connected.value = false
	}

	private companion object {
		const val TAG = "StompClient"
	}
}
