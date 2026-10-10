package ru.ravel.qrref.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.ReceivedItem
import ru.ravel.qrref.UiState
import ru.ravel.qrref.util.QrLink

@Composable
fun ReceiveScreen(state: UiState, vm: MainViewModel) {
	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		item {
			MyQrBlock(state, hint = stringResource(R.string.receive_hint))
		}
		item {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(stringResource(R.string.auto_open_links), style = MaterialTheme.typography.bodyLarge)
				Switch(checked = state.autoOpenUrl, onCheckedChange = vm::setAutoOpenUrl)
			}
		}
		if (state.received.isEmpty()) {
			item {
				Text(
					stringResource(R.string.received_empty),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp),
				)
			}
		} else {
			items(state.received, key = { it.id }) { item ->
				ReceivedCard(item, vm)
			}
		}
	}
}

@Composable
fun MyQrBlock(state: UiState, hint: String) {
	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		QrCode(QrLink.build(state.serverUrl, state.key))
		Text(
			text = stringResource(if (state.connected) R.string.status_online else R.string.status_offline),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = hint,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

@Composable
private fun ReceivedCard(item: ReceivedItem, vm: MainViewModel) {
	val context = LocalContext.current
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			when (item) {
				is ReceivedItem.Text -> {
					SelectionContainer {
						Text(item.text, style = MaterialTheme.typography.bodyLarge)
					}
					Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						TextButton(onClick = {
							copyToClipboard(context, item.text)
							vm.message(R.string.copied)
						}) { Text(stringResource(R.string.action_copy)) }
						TextButton(onClick = { shareText(context, item.text) }) {
							Text(stringResource(R.string.action_share))
						}
						QrLink.asWebUrl(item.text)?.let { url ->
							TextButton(onClick = { openUrl(context, url, vm) }) {
								Text(stringResource(R.string.action_open))
							}
						}
					}
				}

				is ReceivedItem.Downloading -> Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
					Text(stringResource(R.string.downloading_file))
				}

				is ReceivedItem.File -> {
					Text(item.name, style = MaterialTheme.typography.bodyLarge)
					Text(
						stringResource(R.string.file_saved_in_downloads),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					TextButton(onClick = { openFile(context, item.uri, item.mime, vm) }) {
						Text(stringResource(R.string.action_open))
					}
				}
			}
		}
	}
}

internal fun copyToClipboard(context: Context, text: String) {
	val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	clipboard.setPrimaryClip(ClipData.newPlainText("qrRef", text))
}

private fun shareText(context: Context, text: String) {
	val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
	context.startActivity(Intent.createChooser(send, null))
}

fun openUrl(context: Context, url: String, vm: MainViewModel) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
	} catch (_: Exception) {
		vm.message(R.string.error_no_app_for_link)
	}
}

internal fun openFile(context: Context, uri: Uri, mime: String, vm: MainViewModel) {
	try {
		context.startActivity(
			Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, mime)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		)
	} catch (_: Exception) {
		vm.message(R.string.error_no_app_for_link)
	}
}
