package ru.ravel.qrref.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.SendStep
import ru.ravel.qrref.UiState
import ru.ravel.qrref.data.PickedFile

/**
 * «Отправка с этого устройства»: сканируем QR другого устройства, затем выбираем, что с ним сделать —
 * отправить текст/файл, начать чат или связать чатом с ним другой компьютер.
 */
@Composable
fun SendScreen(state: UiState, vm: MainViewModel) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		when (state.sendStep) {
			SendStep.SCAN -> ScanStep(state, vm, hint = stringResource(R.string.send_scan_hint))

			SendStep.SCAN_SECOND -> {
				ScanStep(state, vm, hint = stringResource(R.string.scan_second_hint))
				OutlinedButton(onClick = vm::backToOptions, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.back))
				}
			}

			SendStep.OPTIONS -> OptionsStep(state, vm)

			SendStep.COMPOSE -> ComposeStep(state, vm)
		}
	}
}

@Composable
private fun ScanStep(state: UiState, vm: MainViewModel, hint: String) {
	if (state.fromShare && state.sendStep == SendStep.SCAN) {
		Text(
			stringResource(R.string.send_from_share),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.primary,
		)
	}
	ScannerPanel(hint = hint, busy = state.sending, onCode = vm::onScanned)
}

@Composable
private fun OptionsStep(state: UiState, vm: MainViewModel) {
	if (state.fromShare) {
		Text(
			stringResource(R.string.send_from_share_options),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.primary,
		)
	}
	Card(Modifier.fillMaxWidth()) {
		Column(
			Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Text(stringResource(R.string.options_title), style = MaterialTheme.typography.titleMedium)
			Text(
				shortKey(state.scannedKey),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Button(onClick = vm::openCompose, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.option_send), textAlign = TextAlign.Center)
			}
			Button(onClick = vm::startChat, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.option_chat), textAlign = TextAlign.Center)
			}
			Button(onClick = vm::startSecondScan, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.option_chat_other), textAlign = TextAlign.Center)
			}
			OutlinedButton(onClick = vm::rescan, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.option_rescan))
			}
		}
	}
}

@Composable
private fun ComposeStep(state: UiState, vm: MainViewModel) {
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		if (uris.isNotEmpty()) vm.addFiles(uris)
	}

	OutlinedTextField(
		value = state.draftText,
		onValueChange = vm::setDraftText,
		label = { Text(stringResource(R.string.send_text_label)) },
		minLines = 3,
		maxLines = 8,
		modifier = Modifier.fillMaxWidth(),
	)
	state.files.forEach { file -> FileRow(file, onRemove = { vm.removeFile(file) }) }
	OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
		Text(stringResource(R.string.send_attach_file))
	}
	Button(
		onClick = vm::sendToScanned,
		enabled = !state.sending && (state.draftText.isNotBlank() || state.files.isNotEmpty()),
		modifier = Modifier.fillMaxWidth(),
	) {
		if (state.sending) {
			CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
		} else {
			Text(stringResource(R.string.compose_send))
		}
	}
	OutlinedButton(onClick = vm::backToOptions, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) {
		Text(stringResource(R.string.back))
	}
}

@Composable
fun ScannerPanel(hint: String, busy: Boolean, onCode: (String) -> Unit, modifier: Modifier = Modifier) {
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Box(
			Modifier
				.fillMaxWidth()
				.aspectRatio(1f)
				.clip(RoundedCornerShape(20.dp))
				.background(Color.Black)
		) {
			CameraPermissionGate(Modifier.fillMaxSize()) {
				QrScanner(onCode = onCode, modifier = Modifier.fillMaxSize())
			}
			if (busy) {
				Box(
					Modifier
						.fillMaxSize()
						.background(Color.Black.copy(alpha = 0.5f)),
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
						CircularProgressIndicator(color = Color.White)
						Text(stringResource(R.string.send_sending), color = Color.White)
					}
				}
			}
		}
		Text(
			hint,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun FileRow(file: PickedFile, onRemove: () -> Unit) {
	val context = LocalContext.current
	Card(Modifier.fillMaxWidth()) {
		Row(
			Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
				if (file.size > 0) {
					Text(
						Formatter.formatShortFileSize(context, file.size),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			IconButton(onClick = onRemove) {
				Icon(Icons.Default.Close, contentDescription = stringResource(R.string.send_remove_file), modifier = Modifier.size(20.dp))
			}
		}
	}
}

private fun shortKey(key: String): String = if (key.length > 12) "${key.take(6)}…${key.takeLast(4)}" else key

