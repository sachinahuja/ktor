package io.ktor.client.engine.cio

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.network.sockets.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import java.io.*
import java.net.*
import java.util.*

class CIOHttpRequest(
        override val call: HttpClientCall,
        private val dispatcher: CoroutineDispatcher,
        requestData: HttpRequestData
) : HttpRequest {
    override val attributes: Attributes = Attributes()
    override val method: HttpMethod = requestData.method
    override val url: Url = requestData.url
    override val headers: Headers = requestData.headers

    override val executionContext: CompletableDeferred<Unit> = CompletableDeferred()

    init {
        require(url.protocol.name == "http") { "CIOEngine support only http yet" }
    }

    override suspend fun execute(content: OutgoingContent): HttpResponse {
        val requestTime = Date()
        val address = InetSocketAddress(url.host, url.port)
        val socket = aSocket().tcp().connect(address)
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel()

        try {
            writeRequest(output, content)

            val origin = Closeable {
                output.close()
                socket.close()
            }

            return input.receiveResponse(call, requestTime, dispatcher, origin)
        } catch (cause: Throwable) {
            socket.close()
            throw cause
        }
    }

    private suspend fun writeRequest(output: ByteWriteChannel, body: OutgoingContent) {
        val builder = RequestResponseBuilder()
        val bodySize = body.headers[HttpHeaders.ContentLength]?.toInt()

        try {
            builder.requestLine(method, url.fullPath, HttpProtocolVersion.HTTP_1_1.toString())
            builder.headerLine("Host", url.hostWithPort)

            if (!headers.contains(HttpHeaders.UserAgent)) {
                builder.headerLine("User-Agent", "CIO/ktor")
            }

            headers.flattenEntries().forEach { (name, value) ->
                builder.headerLine(name, value)
            }

            body.headers.flattenEntries().forEach { (name, value) ->
                builder.headerLine(name, value)
            }

            builder.emptyLine()
            output.writePacket(builder.build())
            output.flush()
        } finally {
            builder.release()
        }

        if (body is OutgoingContent.NoContent) return
        val chunked = bodySize == null || body.headers[HttpHeaders.TransferEncoding] == "chunked" || headers[HttpHeaders.TransferEncoding] == "chunked"

        launch(dispatcher, parent = executionContext) {
            val chunkedJob: EncoderJob? = if (chunked) encodeChunked(output, coroutineContext) else null
            val channel = chunkedJob?.channel ?: output

            try {
                channel.writeBody(body)
            } catch (cause: Throwable) {
                channel.close(cause)
                executionContext.completeExceptionally(cause)
            } finally {
                channel.close()
                chunkedJob?.join()
                executionContext.complete(Unit)
            }
        }
    }

    private suspend fun ByteWriteChannel.writeBody(body: OutgoingContent) {
        when (body) {
            is OutgoingContent.NoContent -> return
            is OutgoingContent.ByteArrayContent -> writeFully(body.bytes())
            is OutgoingContent.ReadChannelContent -> body.readFrom().copyTo(this)
            is OutgoingContent.WriteChannelContent -> body.writeTo(this)
            is OutgoingContent.ProtocolUpgrade -> throw UnsupportedContentTypeException(body)
        }
    }
}

