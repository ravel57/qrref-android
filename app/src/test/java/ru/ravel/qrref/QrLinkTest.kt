package ru.ravel.qrref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ravel.qrref.util.QrLink

class QrLinkTest {

	@Test
	fun `parses key from url with scheme`() {
		val qr = QrLink.parse("https://qrref.io/?key=abc123")!!
		assertEquals("abc123", qr.key)
		assertFalse(qr.bridge)
	}

	@Test
	fun `parses key from url without scheme like the server QR`() {
		val qr = QrLink.parse("qrref.io/?key=AbC_d-9")!!
		assertEquals("AbC_d-9", qr.key)
	}

	@Test
	fun `accepts k alias`() {
		assertEquals("zzz", QrLink.parse("https://x.ru/?k=zzz")!!.key)
	}

	@Test
	fun `key param wins over k`() {
		assertEquals("one", QrLink.parse("https://x.ru/?key=one&k=two")!!.key)
	}

	@Test
	fun `missing key gives empty key`() {
		assertEquals("", QrLink.parse("https://qrref.io/")!!.key)
	}

	@Test
	fun `unsafe key is dropped`() {
		assertEquals("", QrLink.parse("https://qrref.io/?key=../../etc")!!.key)
		assertEquals("", QrLink.parse("https://qrref.io/?key=a%2Fb")!!.key)
	}

	@Test
	fun `detects bridge qr by a and b`() {
		val qr = QrLink.parse("https://qrref.io/?a=first&b=second")!!
		assertTrue(qr.bridge)
		assertTrue(qr.hasPair)
		assertEquals("first", qr.a)
		assertEquals("second", qr.b)
	}

	@Test
	fun `detects bridge qr by path and flag`() {
		assertTrue(QrLink.parse("https://qrref.io/bridge?key=k1")!!.bridge)
		assertTrue(QrLink.parse("https://qrref.io/?key=k1&bridge=1")!!.bridge)
	}

	@Test
	fun `rejects garbage`() {
		assertNull(QrLink.parse(""))
		assertNull(QrLink.parse("   "))
		assertNull(QrLink.parse("hello world"))
	}

	@Test
	fun `builds link`() {
		assertEquals("https://qrref.io/?key=k", QrLink.build("https://qrref.io", "k"))
		assertEquals("https://qrref.io/?key=k", QrLink.build("https://qrref.io/", "k"))
		assertEquals("key", QrLink.parse(QrLink.build("http://10.0.2.2:8080", "key"))!!.key)
	}

	@Test
	fun `asWebUrl only accepts a single http link`() {
		assertEquals("https://example.com/a?b=1", QrLink.asWebUrl("  https://example.com/a?b=1\n"))
		assertEquals("http://example.com", QrLink.asWebUrl("http://example.com"))
		assertNull(QrLink.asWebUrl("example.com"))
		assertNull(QrLink.asWebUrl("see https://example.com"))
		assertNull(QrLink.asWebUrl("javascript:alert(1)"))
		assertNull(QrLink.asWebUrl("file:///etc/passwd"))
		assertNull(QrLink.asWebUrl("https://"))
	}

	@Test
	fun `normalizes server url`() {
		assertEquals("https://qrref.io", QrLink.normalizeServerUrl("qrref.io"))
		assertEquals("https://qrref.io", QrLink.normalizeServerUrl(" https://qrref.io/ "))
		assertEquals("http://10.0.2.2:8080", QrLink.normalizeServerUrl("http://10.0.2.2:8080"))
		assertNull(QrLink.normalizeServerUrl(""))
		assertNull(QrLink.normalizeServerUrl("ftp://qrref.io"))
		assertNull(QrLink.normalizeServerUrl("not a url"))
		assertNotNull(QrLink.normalizeServerUrl("localhost:8080"))
	}
}
