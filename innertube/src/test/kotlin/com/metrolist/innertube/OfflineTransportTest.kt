package com.metrolist.innertube

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTransportTest {
    @Test
    fun `existing transport blocks offline requests and resumes online`() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val innerTube = InnerTube()
        val client = innerTube.extractionTransport().httpClient
        val url = "http://127.0.0.1:${server.address.port}/"
        try {
            innerTube.offlineMode = true
            assertTrue(runCatching { client.get(url) }.isFailure)
            assertEquals(0, requests.get())
            innerTube.offlineMode = false
            assertEquals(200, client.get(url).status.value)
            assertEquals(1, requests.get())
            innerTube.offlineMode = true
            assertTrue(runCatching { client.get(url) }.isFailure)
            assertEquals(1, requests.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
