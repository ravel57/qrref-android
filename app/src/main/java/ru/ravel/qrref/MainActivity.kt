package ru.ravel.qrref

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.ravel.qrref.data.ThemeMode
import ru.ravel.qrref.ui.QrrefApp
import ru.ravel.qrref.ui.theme.QrrefTheme

class MainActivity : ComponentActivity() {

	private val viewModel: MainViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		// При повороте экрана и т.п. intent остаётся прежним — «Поделиться» разбираем только при первом запуске.
		if (savedInstanceState == null) handleShare(intent)

		lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) = viewModel.onAppStart()
			override fun onStop(owner: LifecycleOwner) = viewModel.onAppStop()
		})

		setContent {
			val state by viewModel.state.collectAsStateWithLifecycle()
			val dark = when (state.themeMode) {
				ThemeMode.SYSTEM -> isSystemInDarkTheme()
				ThemeMode.LIGHT -> false
				ThemeMode.DARK -> true
			}
			QrrefTheme(darkTheme = dark) {
				QrrefApp(viewModel)
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleShare(intent)
	}

	/** Приложение указано в манифесте как получатель «Поделиться» (ACTION_SEND / ACTION_SEND_MULTIPLE). */
	private fun handleShare(intent: Intent?) {
		when (intent?.action) {
			Intent.ACTION_SEND -> {
				val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
				val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
				viewModel.onShared(text, listOfNotNull(stream))
			}

			Intent.ACTION_SEND_MULTIPLE -> {
				val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
				val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
				viewModel.onShared(text, streams.orEmpty())
			}
		}
	}
}
