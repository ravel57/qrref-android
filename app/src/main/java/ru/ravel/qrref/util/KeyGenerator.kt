package ru.ravel.qrref.util

import java.security.SecureRandom

/** Тот же алфавит, что и у сервера (KeyService). */
object KeyGenerator {

	private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
	private val random = SecureRandom()

	fun generate(length: Int = 20): String = buildString(length) {
		repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
	}
}
