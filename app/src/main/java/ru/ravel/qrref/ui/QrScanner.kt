package ru.ravel.qrref.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/** Превью камеры, которое непрерывно ищет QR-код и сообщает его содержимое в [onCode]. */
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val currentOnCode by rememberUpdatedState(onCode)
	val previewView = remember {
		PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
	}

	DisposableEffect(lifecycleOwner) {
		val executor = Executors.newSingleThreadExecutor()
		val providerFuture = ProcessCameraProvider.getInstance(context)
		var provider: ProcessCameraProvider? = null
		var disposed = false

		providerFuture.addListener({
			if (disposed) return@addListener
			val cameraProvider = providerFuture.get()
			provider = cameraProvider

			val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
			val analysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.setResolutionSelector(
					ResolutionSelector.Builder()
						.setResolutionStrategy(
							ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
						)
						.build()
				)
				.build()
				.also { it.setAnalyzer(executor, QrAnalyzer { code -> currentOnCode(code) }) }

			try {
				cameraProvider.unbindAll()
				cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
			} catch (_: Exception) {
				// нет камеры или она занята — остаётся пустое превью
			}
		}, ContextCompat.getMainExecutor(context))

		onDispose {
			disposed = true
			provider?.unbindAll()
			executor.shutdown()
		}
	}

	AndroidView(factory = { previewView }, modifier = modifier)
}

private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {

	private val reader = MultiFormatReader().apply {
		setHints(
			mapOf(
				DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
				DecodeHintType.TRY_HARDER to true,
			)
		)
	}

	override fun analyze(image: ImageProxy) {
		try {
			// Достаточно яркостной плоскости (Y); QR распознаётся при любом повороте кадра.
			val plane = image.planes[0]
			val buffer = plane.buffer
			val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
			val source = PlanarYUVLuminanceSource(
				data, plane.rowStride, image.height, 0, 0, image.width, image.height, false
			)
			val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
			result.text?.let(onCode)
		} catch (_: Exception) {
			// в кадре нет читаемого QR — нормальная ситуация
		} finally {
			reader.reset()
			image.close()
		}
	}
}
