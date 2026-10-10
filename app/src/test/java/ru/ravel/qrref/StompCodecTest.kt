package ru.ravel.qrref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.ravel.qrref.data.QrRefApi
import ru.ravel.qrref.data.StompCodec

class StompCodecTest {

	@Test
	fun `encodes subscribe frame`() {
		val frame = StompCodec.encode("SUBSCRIBE", mapOf("id" to "sub-0", "destination" to "/topic/activity/k"))
		assertEquals("SUBSCRIBE\nid:sub-0\ndestination:/topic/activity/k\n\n\u0000", frame)
	}

	@Test
	fun `decodes message frame with russian body`() {
		val raw = "MESSAGE\nsubscription:sub-0\nmessage-id:abc-1\ndestination:/topic/activity/k\n" +
				"content-type:text/plain;charset=UTF-8\ncontent-length:12\n\nПривет, мир\u0000"
		val frame = StompCodec.decode(raw)!!
		assertEquals("MESSAGE", frame.command)
		assertEquals("sub-0", frame.headers["subscription"])
		assertEquals("/topic/activity/k", frame.headers["destination"])
		assertEquals("Привет, мир", frame.body)
	}

	@Test
	fun `decodes connected frame without body`() {
		val frame = StompCodec.decode("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\u0000")!!
		assertEquals("CONNECTED", frame.command)
		assertEquals("1.2", frame.headers["version"])
		assertEquals("", frame.body)
	}

	@Test
	fun `body may contain blank lines and colons`() {
		val frame = StompCodec.decode("MESSAGE\nsubscription:s\n\nline1\n\nline3: value\u0000")!!
		assertEquals("line1\n\nline3: value", frame.body)
	}

	@Test
	fun `json body of a room message`() {
		val json = """{"type":"text","from":"k","text":"hi","ts":1}"""
		val frame = StompCodec.decode("MESSAGE\nsubscription:s\ncontent-type:application/json\n\n$json\u0000")!!
		assertEquals(json, frame.body)
	}

	@Test
	fun `heart-beat is ignored`() {
		assertNull(StompCodec.decode("\n"))
		assertNull(StompCodec.decode(""))
	}

	@Test
	fun `unescapes header values and keeps first duplicate`() {
		val frame = StompCodec.decode("MESSAGE\ndestination:/a\\cb\nsubscription:one\nsubscription:two\n\n\u0000")!!
		assertEquals("/a:b", frame.headers["destination"])
		assertEquals("one", frame.headers["subscription"])
	}

	@Test
	fun `decodes CRLF frames`() {
		val frame = StompCodec.decode("MESSAGE\r\nsubscription:s\r\n\r\nbody\u0000")!!
		assertEquals("s", frame.headers["subscription"])
		assertEquals("body", frame.body)
	}

	@Test
	fun `file name from content disposition`() {
		assertEquals("Foto_1.jpg", QrRefApi.fileNameFrom("attachment; filename=Foto_1.jpg"))
		assertEquals("my file.txt", QrRefApi.fileNameFrom("attachment; filename=\"my file.txt\""))
		assertEquals("evil.sh", QrRefApi.fileNameFrom("attachment; filename=../../evil.sh"))
		assertEquals(
			"Отчёт телефона.txt",
			QrRefApi.fileNameFrom("attachment; filename=\"_____ ________.txt\"; filename*=UTF-8''%D0%9E%D1%82%D1%87%D1%91%D1%82%20%D1%82%D0%B5%D0%BB%D0%B5%D1%84%D0%BE%D0%BD%D0%B0.txt")
		)
		assertEquals("qrref-file", QrRefApi.fileNameFrom(null))
		assertEquals("qrref-file", QrRefApi.fileNameFrom("attachment"))
	}
}
