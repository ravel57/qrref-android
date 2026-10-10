package ru.ravel.qrref

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import ru.ravel.qrref.data.ApiException
import ru.ravel.qrref.data.PickedFile
import ru.ravel.qrref.data.Prefs
import ru.ravel.qrref.data.QrRefApi
import ru.ravel.qrref.data.StompClient
import ru.ravel.qrref.data.ThemeMode
import ru.ravel.qrref.util.KeyGenerator
import ru.ravel.qrref.util.QrLink
import ru.ravel.qrref.util.ScannedQr
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainViewModel(app: Application) : AndroidViewModel(app) {

	private val prefs = Prefs(app)

	private val _state = MutableStateFlow(
		UiState(
			serverUrl = prefs.serverUrl,
			key = prefs.key,
			autoOpenUrl = prefs.autoOpenUrl,
			themeMode = prefs.themeMode,
		)
	)
	val state: StateFlow<UiState> = _state.asStateFlow()

	private val _events = Channel<UiEvent>(Channel.BUFFERED)
	val events = _events.receiveAsFlow()

	private val http = OkHttpClient.Builder()
		.pingInterval(20, TimeUnit.SECONDS)
		.build()
	private val api = QrRefApi(http, { app.contentResolver }) { _state.value.serverUrl }
	private val stomp = StompClient(http, viewModelScope)

	private var nextId = 0L
	private var lastScan = ""
	private var lastScanAt = 0L

	init {
		val savedA = prefs.bridgeA
		if (savedA.isNotEmpty()) {
			_state.update {
				it.copy(
					pairing = Pairing(
						active = true,
						aKey = savedA,
						status = string(R.string.pair_saved_a),
					)
				)
			}
		}
		subscribeKeyTopics(_state.value.key)
		viewModelScope.launch {
			stomp.connected.collect { connected ->
				_state.update { it.copy(connected = connected) }
				if (connected) refreshRoom()
			}
		}
	}

	// ---- жизненный цикл ----

	/** Пока приложение на экране — держим соединение, чтобы получать данные. */
	fun onAppStart() = stomp.start(wsUrl())

	fun onAppStop() = stomp.stop()

	fun selectScreen(screen: Screen) = _state.update { it.copy(screen = screen, scanningPeer = false) }

	// ---- «Поделиться» и выбор файлов ----

	fun onShared(text: String?, uris: List<Uri>) {
		viewModelScope.launch {
			val files = describe(uris)
			_state.update {
				it.copy(
					screen = Screen.SEND,
					draftText = text.orEmpty(),
					files = files,
					fromShare = true,
					pairing = it.pairing.copy(active = false),
				)
			}
		}
	}

	fun addFiles(uris: List<Uri>) {
		viewModelScope.launch {
			val added = describe(uris)
			_state.update { it.copy(files = (it.files + added).distinctBy { f -> f.uri }) }
		}
	}

	fun removeFile(file: PickedFile) = _state.update { it.copy(files = it.files - file) }

	fun setDraftText(text: String) = _state.update { it.copy(draftText = text) }

	// ---- отправка ----

	fun onScanned(raw: String) {
		val now = SystemClock.elapsedRealtime()
		if (raw == lastScan && now - lastScanAt < REPEAT_SCAN_MS) return
		lastScan = raw
		lastScanAt = now

		val current = _state.value
		if (current.sending) return

		val link = QrLink.parse(raw) ?: return message(R.string.error_invalid_qr)

		when {
			current.screen == Screen.EXCHANGE -> pairWithPeer(link)
			current.pairing.active || link.bridge -> handlePairing(link)
			link.key.isEmpty() -> message(R.string.error_no_key)
			current.draftText.isBlank() && current.files.isEmpty() -> message(R.string.nothing_to_send)
			else -> send(link.key)
		}
	}

	private fun send(key: String) {
		_state.update { it.copy(sending = true) }
		viewModelScope.launch {
			try {
				val text = _state.value.draftText
				if (text.isNotBlank()) {
					api.sendText(key, text)
					_state.update { it.copy(draftText = "") }
				}
				for (file in _state.value.files) {
					api.sendFile(key, file)
					_state.update { it.copy(files = it.files - file) }
				}
				_state.update { it.copy(fromShare = false) }
				message(R.string.sent)
			} catch (e: Exception) {
				rethrowCancellation(e)
				message(errorText(e))
			} finally {
				_state.update { it.copy(sending = false) }
			}
		}
	}

	// ---- сопряжение (bridge) ----

	fun startPairing() {
		val savedA = prefs.bridgeA
		_state.update {
			val aKey = it.pairing.aKey.ifEmpty { savedA }
			it.copy(
				pairing = Pairing(
					active = true,
					aKey = aKey,
					bKey = it.pairing.bKey,
					status = string(if (aKey.isEmpty()) R.string.pair_hint_first else R.string.pair_hint_second),
				)
			)
		}
	}

	fun resetPairing() {
		prefs.bridgeA = ""
		_state.update {
			it.copy(pairing = Pairing(active = true, status = string(R.string.pair_was_reset)))
		}
	}

	fun exitPairing() = _state.update { it.copy(pairing = it.pairing.copy(active = false)) }

	private fun handlePairing(link: ScannedQr) {
		if (link.hasPair) {
			createBridge(link.a, link.b)
			return
		}
		if (link.key.isEmpty()) {
			setPairing { it.copy(active = true, status = string(R.string.error_no_key), statusKind = StatusKind.ERR) }
			return
		}

		val aKey = _state.value.pairing.aKey
		when {
			aKey.isEmpty() -> {
				prefs.bridgeA = link.key
				setPairing {
					it.copy(
						active = true,
						aKey = link.key,
						status = string(R.string.pair_a_opened),
						statusKind = StatusKind.HINT,
					)
				}
			}

			aKey == link.key -> setPairing {
				it.copy(active = true, status = string(R.string.pair_same_qr), statusKind = StatusKind.ERR)
			}

			else -> {
				setPairing { it.copy(active = true, bKey = link.key) }
				createBridge(aKey, link.key)
			}
		}
	}

	private fun createBridge(a: String, b: String) {
		setPairing { it.copy(status = string(R.string.pair_creating), statusKind = StatusKind.HINT) }
		_state.update { it.copy(sending = true) }
		viewModelScope.launch {
			try {
				api.createBridge(a, b)
				prefs.bridgeA = ""
				setPairing {
					Pairing(active = true, status = string(R.string.pair_done), statusKind = StatusKind.OK)
				}
			} catch (e: Exception) {
				rethrowCancellation(e)
				setPairing { it.copy(status = string(R.string.pair_failed), statusKind = StatusKind.ERR) }
			} finally {
				_state.update { it.copy(sending = false) }
			}
		}
	}

	private fun setPairing(transform: (Pairing) -> Pairing) =
		_state.update { it.copy(pairing = transform(it.pairing)) }

	// ---- обмен ----

	fun setScanningPeer(scanning: Boolean) = _state.update { it.copy(scanningPeer = scanning) }

	/** Телефон сам становится одной из сторон моста: свой ключ + ключ отсканированного собеседника. */
	private fun pairWithPeer(link: ScannedQr) {
		val myKey = _state.value.key
		val peerKey = link.key
		when {
			peerKey.isEmpty() -> message(R.string.error_no_key)
			peerKey == myKey -> message(R.string.pair_same_qr)
			else -> viewModelScope.launch {
				try {
					val roomId = api.createBridge(myKey, peerKey)
					_state.update { it.copy(scanningPeer = false) }
					// roomId есть в ответе — не ждём сообщения по сокету, оно могло потеряться при переподключении
					roomId?.let(::attachRoom)
					message(R.string.chat_paired)
				} catch (e: Exception) {
					rethrowCancellation(e)
					message(R.string.pair_failed)
				}
			}
		}
	}

	fun setChatDraft(text: String) = _state.update { it.copy(chatDraft = text) }

	fun sendChat() {
		val text = _state.value.chatDraft.trim()
		if (text.isEmpty()) return
		viewModelScope.launch {
			try {
				api.sendRoomText(_state.value.key, text)
				_state.update { if (it.chatDraft.trim() == text) it.copy(chatDraft = "") else it }
			} catch (e: Exception) {
				rethrowCancellation(e)
				message(errorText(e))
			}
		}
	}

	private fun attachRoom(roomId: String) {
		if (roomId.isEmpty()) return
		val previous = _state.value.roomId
		if (previous == roomId) return
		previous?.let { stomp.unsubscribe(roomTopic(it)) }
		_state.update { it.copy(roomId = roomId, chat = emptyList(), scanningPeer = false) }
		stomp.subscribe(roomTopic(roomId), ::onRoomMessage)
	}

	private fun refreshRoom() {
		viewModelScope.launch {
			try {
				api.roomOf(_state.value.key)?.let(::attachRoom)
			} catch (e: Exception) {
				rethrowCancellation(e)
				// не сопряжено или сервер недоступен — ничего страшного
			}
		}
	}

	private fun onRoomMessage(body: String) {
		val json = try {
			JSONObject(body)
		} catch (_: Exception) {
			return
		}
		val mine = json.optString("from") == _state.value.key
		val ts = json.optLong("ts", System.currentTimeMillis())
		val msg = when (json.optString("type")) {
			"text" -> ChatMessage(nextId++, json.optString("text"), mine, ts)
			"file" -> ChatMessage(
				id = nextId++, text = "", mine = mine, ts = ts,
				file = ChatFile(
					name = json.optString("name").ifEmpty { "file" },
					size = json.optLong("size", -1),
					path = json.optString("path"),
				),
			)

			else -> return
		}
		_state.update { it.copy(chat = it.chat + msg) }
	}

	/** Скачивает файл из чата в «Загрузки». */
	fun downloadChatFile(messageId: Long) {
		val file = _state.value.chat.firstOrNull { it.id == messageId }?.file ?: return
		if (file.status == ChatFileStatus.DOWNLOADING) return
		updateChatFile(messageId) { it.copy(status = ChatFileStatus.DOWNLOADING) }
		viewModelScope.launch {
			try {
				val saved = api.download(file.path)
				updateChatFile(messageId) {
					it.copy(status = ChatFileStatus.SAVED, savedUri = saved.uri, mime = saved.mime, name = saved.name)
				}
				message(string(R.string.file_saved_in_downloads))
			} catch (e: Exception) {
				rethrowCancellation(e)
				updateChatFile(messageId) { it.copy(status = ChatFileStatus.IDLE) }
				message(R.string.error_download)
			}
		}
	}

	private fun updateChatFile(messageId: Long, transform: (ChatFile) -> ChatFile) = _state.update { state ->
		state.copy(chat = state.chat.map { m ->
			if (m.id == messageId && m.file != null) m.copy(file = transform(m.file)) else m
		})
	}

	/** Отправляет выбранные файлы собеседнику по очереди. */
	fun sendChatFiles(uris: List<Uri>) {
		if (uris.isEmpty()) return
		viewModelScope.launch {
			val files = describe(uris)
			try {
				for (file in files) {
					_state.update { it.copy(uploadingChatFile = file.name) }
					api.sendRoomFile(_state.value.key, file)
				}
			} catch (e: Exception) {
				rethrowCancellation(e)
				message(errorText(e))
			} finally {
				_state.update { it.copy(uploadingChatFile = null) }
			}
		}
	}

	// ---- прием ----

	private fun onActivityMessage(body: String) {
		val path = body.trim()
		if (FILE_PATH.matches(path)) {
			downloadFile(path)
			return
		}
		val item = ReceivedItem.Text(nextId++, System.currentTimeMillis(), body)
		_state.update { it.copy(received = listOf<ReceivedItem>(item) + it.received) }
		if (_state.value.autoOpenUrl) {
			QrLink.asWebUrl(body)?.let { _events.trySend(UiEvent.OpenUrl(it)) }
		}
	}

	private fun downloadFile(path: String) {
		val id = nextId++
		val placeholder = ReceivedItem.Downloading(id, System.currentTimeMillis())
		_state.update { it.copy(received = listOf<ReceivedItem>(placeholder) + it.received) }
		viewModelScope.launch {
			try {
				val saved = api.download(path)
				replaceReceived(id, ReceivedItem.File(id, placeholder.ts, saved.name, saved.uri, saved.mime))
			} catch (e: Exception) {
				rethrowCancellation(e)
				replaceReceived(id, null)
				message(R.string.error_download)
			}
		}
	}

	private fun replaceReceived(id: Long, item: ReceivedItem?) = _state.update { state ->
		state.copy(received = state.received.mapNotNull { if (it.id == id) item else it })
	}

	fun clearReceived() = _state.update { it.copy(received = emptyList()) }

	// ---- настройки ----

	fun setAutoOpenUrl(enabled: Boolean) {
		prefs.autoOpenUrl = enabled
		_state.update { it.copy(autoOpenUrl = enabled) }
	}

	fun setThemeMode(mode: ThemeMode) {
		prefs.themeMode = mode
		_state.update { it.copy(themeMode = mode) }
	}

	/** Возвращает false, если адрес некорректен. */
	fun setServerUrl(input: String): Boolean {
		val url = QrLink.normalizeServerUrl(input) ?: return false
		if (url == _state.value.serverUrl) return true
		prefs.serverUrl = url
		val roomId = _state.value.roomId
		roomId?.let { stomp.unsubscribe(roomTopic(it)) }
		_state.update { it.copy(serverUrl = url, roomId = null, chat = emptyList()) }
		// Клиент перезапустится на новом адресе (stop сбрасывает адрес, но подписки сохраняются).
		stomp.stop()
		stomp.start(wsUrl())
		return true
	}

	fun regenerateKey() {
		val old = _state.value.key
		val key = KeyGenerator.generate()
		prefs.key = key
		stomp.unsubscribe(activityTopic(old))
		stomp.unsubscribe(bridgeTopic(old))
		_state.value.roomId?.let { stomp.unsubscribe(roomTopic(it)) }
		_state.update { it.copy(key = key, roomId = null, chat = emptyList(), scanningPeer = false) }
		subscribeKeyTopics(key)
	}

	// ---- вспомогательное ----

	private fun subscribeKeyTopics(key: String) {
		stomp.subscribe(activityTopic(key), ::onActivityMessage)
		stomp.subscribe(bridgeTopic(key)) { attachRoom(it.trim()) }
	}

	private fun wsUrl(): String = _state.value.serverUrl.toHttpUrl().newBuilder()
		.addPathSegment("websocket")
		.addPathSegment("websocket")
		.build()
		.toString()

	private suspend fun describe(uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
		val resolver = getApplication<Application>().contentResolver
		uris.map { uri ->
			var name: String? = null
			var size = -1L
			try {
				resolver.query(uri, null, null, null, null)?.use { cursor ->
					if (cursor.moveToFirst()) {
						cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
							?.let { name = cursor.getString(it) }
						cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
							?.let { size = cursor.getLong(it) }
					}
				}
			} catch (_: Exception) {
				// провайдер не умеет отдавать метаданные — используем значения по умолчанию
			}
			PickedFile(
				uri = uri,
				name = name ?: uri.lastPathSegment ?: "file",
				size = size,
				mime = resolver.getType(uri),
			)
		}
	}

	private fun errorText(e: Exception): String = when {
		e is ApiException && e.code == 409 -> string(R.string.error_not_paired)
		e is ApiException -> string(R.string.error_server, e.code)
		e is IOException -> string(R.string.error_network)
		else -> e.message ?: string(R.string.error_unknown)
	}

	private fun rethrowCancellation(e: Exception) {
		if (e is CancellationException) throw e
	}

	fun message(@StringRes res: Int) = message(string(res))

	fun message(text: String) {
		_events.trySend(UiEvent.Message(text))
	}

	private fun string(@StringRes res: Int, vararg args: Any): String =
		getApplication<Application>().getString(res, *args)

	private companion object {
		const val REPEAT_SCAN_MS = 4_000L
		val FILE_PATH = Regex("^file/[A-Za-z0-9]{20,64}$")

		fun activityTopic(key: String) = "/topic/activity/$key"
		fun bridgeTopic(key: String) = "/topic/bridge/$key"
		fun roomTopic(roomId: String) = "/topic/room/$roomId"
	}
}
