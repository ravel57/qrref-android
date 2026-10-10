package ru.ravel.qrref

import android.net.Uri
import ru.ravel.qrref.data.PickedFile
import ru.ravel.qrref.data.ThemeMode

enum class Screen { RECEIVE, SEND, EXCHANGE }

enum class StatusKind { HINT, OK, ERR }

data class Pairing(
	val active: Boolean = false,
	val aKey: String = "",
	val bKey: String = "",
	val status: String = "",
	val statusKind: StatusKind = StatusKind.HINT,
)

sealed interface ReceivedItem {
	val id: Long
	val ts: Long

	data class Text(override val id: Long, override val ts: Long, val text: String) : ReceivedItem

	data class Downloading(override val id: Long, override val ts: Long) : ReceivedItem

	data class File(
		override val id: Long,
		override val ts: Long,
		val name: String,
		val uri: Uri,
		val mime: String,
	) : ReceivedItem
}

enum class ChatFileStatus { IDLE, DOWNLOADING, SAVED }

/** Файл, присланный в чат: [path] — путь для скачивания на сервере (`room/file/<id>`). */
data class ChatFile(
	val name: String,
	val size: Long,
	val path: String,
	val status: ChatFileStatus = ChatFileStatus.IDLE,
	val savedUri: Uri? = null,
	val mime: String = "application/octet-stream",
)

data class ChatMessage(
	val id: Long,
	val text: String,
	val mine: Boolean,
	val ts: Long,
	val file: ChatFile? = null,
)

data class UiState(
	val screen: Screen = Screen.SEND,
	val serverUrl: String,
	val key: String,
	val autoOpenUrl: Boolean,
	val themeMode: ThemeMode,
	val connected: Boolean = false,

	// Прием
	val received: List<ReceivedItem> = emptyList(),

	// Отправка
	val draftText: String = "",
	val files: List<PickedFile> = emptyList(),
	val fromShare: Boolean = false,
	val sending: Boolean = false,
	val pairing: Pairing = Pairing(),

	// Обмен
	val roomId: String? = null,
	val chat: List<ChatMessage> = emptyList(),
	val chatDraft: String = "",
	val scanningPeer: Boolean = false,
	val uploadingChatFile: String? = null,
)

sealed interface UiEvent {
	data class Message(val text: String) : UiEvent
	data class OpenUrl(val url: String) : UiEvent
}
