package ru.ravel.qrref

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import ru.ravel.qrref.data.QrRefApi
import ru.ravel.qrref.data.StompClient
import ru.ravel.qrref.util.KeyGenerator
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Проверяет клиент против живого бэкенда qrRef. Выключен по умолчанию; включается переменной окружения:
 * `QRREF_TEST_SERVER=http://localhost:8080 ./gradlew :app:testDebugUnitTest --tests '*BackendIntegrationTest'`
 */
class BackendIntegrationTest {

	private val server: String? = System.getenv("QRREF_TEST_SERVER")?.trimEnd('/')
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private lateinit var http: OkHttpClient
	private lateinit var api: QrRefApi
	private lateinit var stomp: StompClient

	@Before
	fun setUp() {
		assumeTrue("QRREF_TEST_SERVER is not set", server != null)
		http = OkHttpClient()
		api = QrRefApi(http, { error("not used") }) { server!! }
		stomp = StompClient(http, scope)
	}

	@After
	fun tearDown() {
		if (server == null) return
		stomp.stop()
		scope.cancel()
	}

	private fun wsUrl() = server!!.toHttpUrl().newBuilder()
		.addPathSegment("websocket").addPathSegment("websocket").build().toString()

	private fun awaitConnected() {
		val deadline = System.currentTimeMillis() + 10_000
		while (!stomp.connected.value && System.currentTimeMillis() < deadline) Thread.sleep(50)
		assertTrue("STOMP did not connect", stomp.connected.value)
	}

	@Test
	fun `text sent through the api arrives on the activity topic`() = runBlocking {
		val key = KeyGenerator.generate()
		val inbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/activity/$key") { inbox.add(it) }
		stomp.start(wsUrl())
		awaitConnected()

		api.sendText(key, "Привет, qrRef! 😀\nвторая строка")
		assertEquals("Привет, qrRef! 😀\nвторая строка", inbox.poll(5, TimeUnit.SECONDS))
	}

	@Test
	fun `subscriptions survive stop and start`() = runBlocking {
		val key = KeyGenerator.generate()
		val inbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/activity/$key") { inbox.add(it) }
		stomp.start(wsUrl())
		awaitConnected()

		stomp.stop()
		assertTrue(!stomp.connected.value)
		stomp.start(wsUrl())
		awaitConnected()

		api.sendText(key, "after restart")
		assertEquals("after restart", inbox.poll(5, TimeUnit.SECONDS))
	}

	@Test
	fun `bridge announces the room and chat messages flow both ways`() = runBlocking {
		val a = KeyGenerator.generate()
		val b = KeyGenerator.generate()
		val bridgeInbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/bridge/$a") { bridgeInbox.add(it.trim()) }
		stomp.start(wsUrl())
		awaitConnected()

		assertNull(api.roomOf(a))
		val created = api.createBridge(a, b)
		assertNotNull("bridge response has no room", created)

		val roomId = bridgeInbox.poll(5, TimeUnit.SECONDS)
		assertNotNull("room id was not announced", roomId)
		assertEquals(created, roomId)
		assertEquals(roomId, api.roomOf(a))
		assertEquals(roomId, api.roomOf(b))

		val roomInbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/room/$roomId") { roomInbox.add(it) }
		Thread.sleep(300)

		api.sendRoomText(a, "от A")
		api.sendRoomText(b, "от B")
		val first = JSONObject(roomInbox.poll(5, TimeUnit.SECONDS)!!)
		val second = JSONObject(roomInbox.poll(5, TimeUnit.SECONDS)!!)
		assertEquals("text", first.getString("type"))
		assertEquals(a, first.getString("from"))
		assertEquals("от A", first.getString("text"))
		assertEquals(b, second.getString("from"))
		assertEquals("от B", second.getString("text"))
	}

	@Test
	fun `file sent to the room arrives as a file message and downloads with a russian name`() = runBlocking {
		val a = KeyGenerator.generate()
		val b = KeyGenerator.generate()
		val roomId = api.createBridge(a, b)!!
		val inbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/room/$roomId") { inbox.add(it) }
		stomp.start(wsUrl())
		awaitConnected()
		Thread.sleep(300)

		val body = MultipartBody.Builder().setType(MultipartBody.FORM)
			.addFormDataPart("file", "Отчёт телефона.txt", "содержимое файла".toRequestBody("text/plain".toMediaType()))
			.build()
		http.newCall(Request.Builder().url("$server/room/file/$a").post(body).build()).execute().use {
			assertTrue("upload failed: ${it.code}", it.isSuccessful)
		}

		val message = JSONObject(inbox.poll(5, TimeUnit.SECONDS)!!)
		assertEquals("file", message.getString("type"))
		assertEquals(a, message.getString("from"))
		assertEquals("Отчёт телефона.txt", message.getString("name"))
		assertTrue(message.getString("path").startsWith("room/file/"))

		// скачать можно и отправителю, и собеседнику, и не один раз
		repeat(2) {
			http.newCall(Request.Builder().url("$server/${message.getString("path")}").build()).execute().use { response ->
				assertEquals(200, response.code)
				assertEquals("Отчёт телефона.txt", QrRefApi.fileNameFrom(response.header("Content-Disposition")))
				assertEquals("содержимое файла", response.body.string())
			}
		}
	}

	@Test
	fun `leaving the room notifies the peer and drops the room for both`() = runBlocking {
		val a = KeyGenerator.generate()
		val b = KeyGenerator.generate()
		val roomId = api.createBridge(a, b)!!
		val inbox = LinkedBlockingQueue<String>()
		stomp.subscribe("/topic/room/$roomId") { inbox.add(it) }
		stomp.start(wsUrl())
		awaitConnected()
		Thread.sleep(300)

		api.leaveRoom(a)

		val closed = JSONObject(inbox.poll(5, TimeUnit.SECONDS)!!)
		assertEquals("closed", closed.getString("type"))
		assertEquals(a, closed.getString("from"))
		assertNull(api.roomOf(a))
		assertNull("the peer must not keep the dead room", api.roomOf(b))
		// после выхода писать в чат нельзя
		val error = runCatching { api.sendRoomText(b, "x") }.exceptionOrNull()
		assertTrue(error is ru.ravel.qrref.data.ApiException && error.code == 409)
	}

	@Test
	fun `leaving without a room is harmless`() = runBlocking {
		api.leaveRoom(KeyGenerator.generate())
	}

	@Test
	fun `re-pairing drops the old room of the former peer`() = runBlocking {
		val a = KeyGenerator.generate()
		val b = KeyGenerator.generate()
		val c = KeyGenerator.generate()
		api.createBridge(a, b)
		val second = api.createBridge(a, c)
		assertEquals(second, api.roomOf(a))
		assertEquals(second, api.roomOf(c))
		assertNull("former peer must not keep a dead room", api.roomOf(b))
	}

	@Test
	fun `bridge with itself or with a bad key is rejected`() = runBlocking {
		val key = KeyGenerator.generate()
		val same = runCatching { api.createBridge(key, key) }.exceptionOrNull()
		assertTrue(same is ru.ravel.qrref.data.ApiException && same.code == 400)
		val bad = runCatching { api.createBridge(key, "../x") }.exceptionOrNull()
		assertTrue(bad is ru.ravel.qrref.data.ApiException && bad.code == 400)
	}

	@Test
	fun `room text before pairing is rejected with 409`() = runBlocking {
		val error = runCatching { api.sendRoomText(KeyGenerator.generate(), "x") }.exceptionOrNull()
		assertTrue(error is ru.ravel.qrref.data.ApiException && error.code == 409)
	}
}
