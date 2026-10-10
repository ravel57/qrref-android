package ru.ravel.qrref.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.ravel.qrref.R
import ru.ravel.qrref.StatusKind
import ru.ravel.qrref.util.QrBitmap

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
	val bitmap = remember(content) { QrBitmap.encode(content).asImageBitmap() }
	// QR всегда чёрный на белом, независимо от темы — иначе его не отсканировать.
	Box(
		modifier
			.clip(RoundedCornerShape(16.dp))
			.background(Color.White)
			.padding(12.dp)
	) {
		Image(
			bitmap = bitmap,
			contentDescription = null,
			filterQuality = FilterQuality.None,
			modifier = Modifier.size(240.dp),
		)
	}
}

@Composable
fun ConnectionDot(connected: Boolean, modifier: Modifier = Modifier) {
	val color = if (connected) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.error
	Box(
		modifier
			.size(10.dp)
			.clip(CircleShape)
			.background(color)
	)
}

@Composable
fun statusColor(kind: StatusKind): Color = when (kind) {
	StatusKind.HINT -> MaterialTheme.colorScheme.onSurfaceVariant
	StatusKind.OK -> Color(0xFF2E9E5B)
	StatusKind.ERR -> MaterialTheme.colorScheme.error
}

/** Показывает [content] только когда есть доступ к камере; иначе объясняет, зачем он нужен. */
@Composable
fun CameraPermissionGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val context = LocalContext.current
	fun isGranted() =
		ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

	var granted by remember { mutableStateOf(isGranted()) }
	var asked by remember { mutableStateOf(false) }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		granted = it
	}

	// вернулись из системных настроек
	LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = isGranted() }

	LaunchedEffect(Unit) {
		if (!granted && !asked) {
			asked = true
			launcher.launch(Manifest.permission.CAMERA)
		}
	}

	if (granted) {
		content()
	} else {
		Column(
			modifier = modifier.padding(16.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
		) {
			Text(
				stringResource(R.string.camera_permission_needed),
				textAlign = TextAlign.Center,
				style = MaterialTheme.typography.bodyMedium,
			)
			Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
				Text(stringResource(R.string.camera_grant))
			}
			TextButton(onClick = {
				context.startActivity(
					Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
				)
			}) {
				Text(stringResource(R.string.camera_open_settings))
			}
		}
	}
}
