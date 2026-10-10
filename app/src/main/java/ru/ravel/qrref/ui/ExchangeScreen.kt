package ru.ravel.qrref.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ravel.qrref.ChatFile
import ru.ravel.qrref.ChatFileStatus
import ru.ravel.qrref.ChatMessage
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExchangeScreen(state: UiState, vm: MainViewModel) {
	if (state.roomId == null) {
		WaitingForPeer(state, vm)
	} else {
		Chat(state, vm)
	}
}

@Composable
private fun WaitingForPeer(state: UiState, vm: MainViewModel) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		if (state.scanningPeer) {
			ScannerPanel(
				hint = stringResource(R.string.exchange_scan_peer_hint),
				busy = false,
				onCode = vm::onScanned,
			)
			OutlinedButton(onClick = { vm.setScanningPeer(false) }) {
				Text(stringResource(R.string.exchange_close_scanner))
			}
		} else {
			MyQrBlock(state, hint = stringResource(R.string.exchange_waiting))
			OutlinedButton(onClick = { vm.setScanningPeer(true) }) {
				Text(stringResource(R.string.exchange_scan_peer))
			}
		}
	}
}

@Composable
private fun Chat(state: UiState, vm: MainViewModel) {
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		vm.sendChatFiles(uris)
	}
	val listState = rememberLazyListState()
	LaunchedEffect(state.chat.size) {
		if (state.chat.isNotEmpty()) listState.animateScrollToItem(state.chat.lastIndex)
	}

	Column(Modifier.fillMaxSize()) {
		if (state.chat.isEmpty()) {
			Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
				Text(
					stringResource(R.string.chat_empty),
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
			}
		} else {
			LazyColumn(
				state = listState,
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentPadding = PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(state.chat, key = { it.id }) { Bubble(it, vm) }
			}
		}

		HorizontalDivider()
		state.uploadingChatFile?.let { name ->
			Text(
				stringResource(R.string.chat_uploading, name),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
			)
		}
		Row(
			Modifier.fillMaxWidth().padding(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = state.uploadingChatFile == null) {
				Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_attach))
			}
			OutlinedTextField(
				value = state.chatDraft,
				onValueChange = vm::setChatDraft,
				label = { Text(stringResource(R.string.chat_message_label)) },
				maxLines = 4,
				keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
				modifier = Modifier.weight(1f),
			)
			IconButton(onClick = vm::sendChat, enabled = state.chatDraft.isNotBlank()) {
				Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
			}
		}
	}
}

@Composable
private fun Bubble(message: ChatMessage, vm: MainViewModel) {
	val time = remember(message.ts) { TIME_FORMAT.format(Date(message.ts)) }
	val context = LocalContext.current
	val haptics = LocalHapticFeedback.current
	val shape = RoundedCornerShape(16.dp)
	Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
		Surface(
			shape = shape,
			color = if (message.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
			modifier = Modifier
				.widthIn(max = 300.dp)
				.clip(shape)
				// удержание копирует текст сообщения (у файла — его имя)
				.combinedClickable(onClick = {}, onLongClick = {
					haptics.performHapticFeedback(HapticFeedbackType.LongPress)
					copyToClipboard(context, message.file?.name ?: message.text)
					vm.message(R.string.copied)
				}),
		) {
			Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
				if (message.file != null) {
					FileContent(message, message.file, vm)
				} else {
					Text(message.text, style = MaterialTheme.typography.bodyLarge)
				}
				Text(
					time,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.align(Alignment.End),
				)
			}
		}
	}
}

@Composable
private fun FileContent(message: ChatMessage, file: ChatFile, vm: MainViewModel) {
	val context = LocalContext.current
	Text(file.name, style = MaterialTheme.typography.bodyLarge)
	if (file.size >= 0) {
		Text(
			formatSize(file.size),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	when (file.status) {
		ChatFileStatus.IDLE -> TextButton(onClick = { vm.downloadChatFile(message.id) }) {
			Text(stringResource(R.string.chat_file_download))
		}

		ChatFileStatus.DOWNLOADING -> Text(
			stringResource(R.string.chat_file_downloading),
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(vertical = 8.dp),
		)

		ChatFileStatus.SAVED -> TextButton(onClick = { file.savedUri?.let { openFile(context, it, file.mime, vm) } }) {
			Text(stringResource(R.string.action_open))
		}
	}
}

private fun formatSize(bytes: Long): String = when {
	bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
	bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
	bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / (1L shl 10).toDouble())
	else -> "$bytes Б"
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
