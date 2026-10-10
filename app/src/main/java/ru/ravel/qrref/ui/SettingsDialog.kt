package ru.ravel.qrref.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.UiState
import ru.ravel.qrref.data.ThemeMode

@Composable
fun SettingsDialog(state: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
	var server by remember { mutableStateOf(state.serverUrl) }
	var serverInvalid by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings)) },
		text = {
			Column(
				Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				OutlinedTextField(
					value = server,
					onValueChange = {
						server = it
						serverInvalid = false
					},
					label = { Text(stringResource(R.string.settings_server)) },
					singleLine = true,
					isError = serverInvalid,
					supportingText = if (serverInvalid) {
						{ Text(stringResource(R.string.settings_server_invalid)) }
					} else null,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					modifier = Modifier.fillMaxWidth(),
				)

				Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleSmall)
				Column {
					ThemeOption(R.string.theme_system, ThemeMode.SYSTEM, state.themeMode, vm)
					ThemeOption(R.string.theme_light, ThemeMode.LIGHT, state.themeMode, vm)
					ThemeOption(R.string.theme_dark, ThemeMode.DARK, state.themeMode, vm)
				}

				OutlinedButton(onClick = {
					vm.regenerateKey()
					onDismiss()
				}) { Text(stringResource(R.string.settings_new_key)) }
				Text(
					stringResource(R.string.settings_new_key_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = {
				if (vm.setServerUrl(server)) onDismiss() else serverInvalid = true
			}) { Text(stringResource(R.string.settings_save)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
		},
	)
}

@Composable
private fun ThemeOption(label: Int, mode: ThemeMode, selected: ThemeMode, vm: MainViewModel) {
	Row(
		Modifier
			.fillMaxWidth()
			.selectable(selected = mode == selected, onClick = { vm.setThemeMode(mode) }, role = Role.RadioButton),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(selected = mode == selected, onClick = null)
		Text(stringResource(label), modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
	}
}
