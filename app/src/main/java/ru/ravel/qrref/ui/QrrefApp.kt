package ru.ravel.qrref.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.ravel.qrref.MainViewModel
import ru.ravel.qrref.R
import ru.ravel.qrref.Screen
import ru.ravel.qrref.UiEvent

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun QrrefApp(vm: MainViewModel) {
	val state by vm.state.collectAsStateWithLifecycle()
	val snackbar = remember { SnackbarHostState() }
	val context = LocalContext.current
	var showSettings by rememberSaveable { mutableStateOf(false) }

	LaunchedEffect(vm) {
		vm.events.collect { event ->
			when (event) {
				is UiEvent.Message -> launch {
					snackbar.currentSnackbarData?.dismiss()
					snackbar.showSnackbar(event.text)
				}

				is UiEvent.OpenUrl -> openUrl(context, event.url, vm)
			}
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.app_name)) },
				actions = {
					ConnectionDot(state.connected)
					IconButton(onClick = { showSettings = true }) {
						Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
					}
				},
			)
		},
		bottomBar = {
			// пока открыта клавиатура, нижняя панель только мешает
			if (!WindowInsets.isImeVisible && state.roomId == null) {
				NavigationBar {
					Screen.entries.forEach { screen ->
						NavigationBarItem(
							selected = state.screen == screen,
							onClick = { vm.selectScreen(screen) },
							icon = { Icon(screen.icon(), contentDescription = null) },
							label = { Text(stringResource(screen.label())) },
						)
					}
				}
			}
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { padding ->
		Box(
			Modifier
				.fillMaxSize()
				.padding(padding)
				.consumeWindowInsets(padding)
				.imePadding()
		) {
			// чат открывается поверх любого режима, пока устройства связаны
			if (state.roomId != null) {
				ChatScreen(state, vm)
			} else {
				when (state.screen) {
					Screen.RECEIVE -> ReceiveScreen(state, vm)
					Screen.SEND -> SendScreen(state, vm)
				}
			}
		}
	}

	if (showSettings) {
		SettingsDialog(state, vm, onDismiss = { showSettings = false })
	}
}

private fun Screen.label(): Int = when (this) {
	Screen.RECEIVE -> R.string.tab_receive
	Screen.SEND -> R.string.tab_send
}

private fun Screen.icon(): ImageVector = when (this) {
	Screen.RECEIVE -> Icons.Default.KeyboardArrowDown
	Screen.SEND -> Icons.AutoMirrored.Filled.Send
}
