package ru.ravel.qrref.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.UiState
import ru.ravel.qrref.data.PickedFile

@Composable
fun SendScreen(state: UiState, vm: MainViewModel) {
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
		if (uris.isNotEmpty()) vm.addFiles(uris)
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		if (state.pairing.active) {
			PairingCard(state, vm)
		} else {
			if (state.fromShare) {
				Text(
					stringResource(R.string.send_from_share),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.primary,
				)
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
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
					Text(stringResource(R.string.send_attach_file))
				}
				TextButton(onClick = vm::startPairing) { Text(stringResource(R.string.pair_start)) }
			}
		}

		ScannerPanel(
			hint = stringResource(R.string.send_scan_hint),
			busy = state.sending,
			onCode = vm::onScanned,
		)
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
	Card(Modifier.fillMaxWidth()) {
		Row(
			Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
				if (file.size > 0) {
					Text(
						formatSize(file.size),
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

@Composable
private fun PairingCard(state: UiState, vm: MainViewModel) {
	val pairing = state.pairing
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)
			Text(
				stringResource(if (pairing.aKey.isEmpty()) R.string.pair_step_a else R.string.pair_step_b),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text("A: ${pairing.aKey.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
			Text("B: ${pairing.bKey.ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
			if (pairing.status.isNotEmpty()) {
				Text(pairing.status, color = statusColor(pairing.statusKind), style = MaterialTheme.typography.bodyMedium)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedButton(onClick = vm::resetPairing) { Text(stringResource(R.string.pair_reset)) }
				OutlinedButton(onClick = vm::exitPairing) { Text(stringResource(R.string.pair_exit)) }
			}
		}
	}
}

private fun formatSize(bytes: Long): String = when {
	bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
	bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
	bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / (1L shl 10).toDouble())
	else -> "$bytes Б"
}
