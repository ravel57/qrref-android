package ru.ravel.qrref.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

object QrBitmap {

	fun encode(text: String, size: Int = 720): Bitmap {
		val hints = mapOf(
			EncodeHintType.CHARACTER_SET to "UTF-8",
			EncodeHintType.MARGIN to 1,
		)
		val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
		val pixels = IntArray(matrix.width * matrix.height)
		for (y in 0 until matrix.height) {
			val row = y * matrix.width
			for (x in 0 until matrix.width) {
				pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
			}
		}
		return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
	}
}
