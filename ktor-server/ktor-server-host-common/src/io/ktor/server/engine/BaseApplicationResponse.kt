package io.ktor.server.engine

import io.ktor.application.*
import io.ktor.cio.*
import io.ktor.content.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.response.*
import kotlinx.coroutines.experimental.io.*

/**
 * Base class for implementing an [ApplicationResponse]
 */
abstract class BaseApplicationResponse(override val call: ApplicationCall) : ApplicationResponse {
    private var _status: HttpStatusCode? = null

    override val cookies by lazy { ResponseCookies(this, call.request.origin.scheme == "https") }

    override fun status() = _status
    override fun status(value: HttpStatusCode) {
        _status = value
        setStatus(value)
    }

    private var responded = false
    override final val pipeline = ApplicationSendPipeline().apply {
        merge(call.application.sendPipeline)
        intercept(ApplicationSendPipeline.Engine) {
            if (responded)
                throw ResponseAlreadySentException()
            val response = subject
            if (response is OutgoingContent) {
                respondOutgoingContent(response)
            } else {
                throw IllegalArgumentException("Response pipeline couldn't transform '${response.javaClass}' to the OutgoingContent")
            }
        }
    }

    protected fun commitHeaders(content: OutgoingContent) {
        responded = true
        content.status?.let { status(it) } ?: status() ?: status(HttpStatusCode.OK)
        content.headers.forEach { name, values ->
            for (value in values) {
                header(name, value)
            }
        }

        if (content is OutgoingContent.ByteArrayContent && content.headers[HttpHeaders.ContentLength] == null) {
            header(HttpHeaders.ContentLength, content.bytes().size)
        }

        val connection = call.request.headers["Connection"]
        if (connection != null) {
            when {
                connection.equals("close", true) -> header("Connection", "close")
                connection.equals("keep-alive", true) -> header("Connection", "keep-alive")
            }
        }
    }

    protected open suspend fun respondOutgoingContent(content: OutgoingContent) {
        val length = content.headers[HttpHeaders.ContentLength]?.toLong()
        when (content) {
            is OutgoingContent.ProtocolUpgrade -> {
                commitHeaders(content)
                return respondUpgrade(content)
            }

        // ByteArrayContent is most efficient
            is OutgoingContent.ByteArrayContent -> {
                // First call user code to acquire bytes, because it could fail
                val bytes = content.bytes()
                // If bytes are fine, commit headers and send data
                commitHeaders(content)
                return respondFromBytes(bytes)
            }

        // WriteChannelContent is more efficient than ReadChannelContent
            is OutgoingContent.WriteChannelContent -> {
                // First set headers
                commitHeaders(content)
                // need to be in external function to keep tail suspend call
                return respondWriteChannelContent(content, length)
            }

        // Pipe is least efficient
            is OutgoingContent.ReadChannelContent -> {
                // First call user code to acquire read channel, because it could fail
                val readChannel = content.readFrom()
                // If channel is fine, commit headers and pipe data
                commitHeaders(content)
                return respondFromChannel(readChannel, length)
            }

        // Do nothing, but maintain `when` exhaustiveness
            is OutgoingContent.NoContent -> { /* no-op */
                commitHeaders(content)
            }
        }
    }

    protected open suspend fun respondWriteChannelContent(content: OutgoingContent.WriteChannelContent, length: Long?) {
        // Retrieve response channel, that might send out headers, so it should go after commitHeaders
        responseChannel(length).use {
            // Call user code to send data
            content.writeTo(this)
        }
    }

    protected open suspend fun respondFromBytes(bytes: ByteArray) {
        responseChannel(bytes.size.toLong()).use {
            writeFully(bytes)
        }
    }

    protected open suspend fun respondFromChannel(readChannel: ByteReadChannel, length: Long?) {
        readChannel.copyAndClose(responseChannel(length))
    }

    protected abstract suspend fun respondUpgrade(upgrade: OutgoingContent.ProtocolUpgrade)
    protected abstract suspend fun responseChannel(length: Long? = null): ByteWriteChannel
    protected open val bufferPool: ByteBufferPool get() = NoPool

    protected abstract fun setStatus(statusCode: HttpStatusCode)

    override fun push(builder: ResponsePushBuilder) {
        link(builder.url.buildString(), LinkHeader.Rel.Prefetch)
    }

    class ResponseAlreadySentException : IllegalStateException("Response has already been sent")
}