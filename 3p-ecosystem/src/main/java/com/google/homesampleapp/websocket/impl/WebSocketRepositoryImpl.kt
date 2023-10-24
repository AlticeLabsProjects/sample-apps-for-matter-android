package com.google.homesampleapp.websocket.impl

import android.os.Build
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.homesampleapp.websocket.WebSocketRepository
import com.google.homesampleapp.websocket.WebSocketRequest
import com.google.homesampleapp.websocket.WebSocketState
import com.google.homesampleapp.websocket.impl.entities.AuthorizationException
import com.google.homesampleapp.websocket.impl.entities.MatterCommissionResponse
import com.google.homesampleapp.websocket.impl.entities.SocketResponse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException

class WebSocketRepositoryImpl @AssistedInject constructor(
    private val okHttpClient: OkHttpClient,
    @Assisted private val webSocketUrl: String,
) : WebSocketRepository, WebSocketListener() {

    companion object {
        private const val TAG = "WebSocketRepository"

        private const val DISCONNECT_DELAY = 10000L

        const val USER_AGENT = "User-Agent"
        val USER_AGENT_STRING = "Matter Sample/1.0.0 (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + Job())
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    private val activeMessages = Collections.synchronizedMap(mutableMapOf<String, WebSocketRequest>())
    private val id = AtomicLong(1)
    private var connection: WebSocket? = null
    private var connectionState: WebSocketState? = null
    private val connectedMutex = Mutex()
    private var connected = CompletableDeferred<Boolean>()

    override fun getConnectionState(): WebSocketState? = connectionState

    override fun getWebSocketUrl(): String = webSocketUrl

    override suspend fun commissionMatterDevice(code: String): MatterCommissionResponse? {
        val response = sendMessage(
            WebSocketRequest(
                message = mapOf(
                    "command" to "commission_with_code",
                    "args" to mapOf(
                        "code" to "$code"
                    )
                ),
                timeout = 120000L // Matter commissioning takes at least 60 seconds + interview
            )
        )

        return response?.let {
            MatterCommissionResponse(
                success = response.result != null,
                nodeId = if (response.result?.has("node_id") == true) {
                    response.result.get("node_id").let {
                        if (it.isNumber) it.asInt() else null
                    }
                } else {
                    null
                },
                errorCode = response.errorCode,
                details = response.details
            )
        }
    }

    override suspend fun commissionMatterDeviceOnNetwork(pin: Long): MatterCommissionResponse? {
        val response = sendMessage(
            WebSocketRequest(
                message = mapOf(
                    "command" to "commission_on_network",
                    "args" to mapOf(
                        "setup_pin_code" to pin
                    )
                ),
                timeout = 120000L // Matter commissioning takes at least 60 seconds + interview
            )
        )

        return response?.let {
            MatterCommissionResponse(
                success = response.result != null,
                nodeId = if (response.result?.has("node_id") == true) {
                    response.result.get("node_id").let {
                        if (it.isNumber) it.asInt() else null
                    }
                } else {
                    null
                },
                errorCode = response.errorCode,
                details = response.details
            )
        }
    }

    override suspend fun removeMatterDevice(nodeId: Long): MatterCommissionResponse? {
        val response = sendMessage(
            WebSocketRequest(
                message = mapOf(
                    "command" to "remove_node",
                    "args" to mapOf(
                        "node_id" to nodeId
                    )
                ),
                timeout = 120000L // Matter commissioning takes at least 60 seconds + interview
            ),
            "remove_node"
        )

        return response?.let {
            MatterCommissionResponse(
                success = response.errorCode == null,
                nodeId = null,
                errorCode = response.errorCode,
                details = response.details
            )
        }
    }

    private suspend fun connect(): Boolean {
        connectedMutex.withLock {
            if (connection != null && connected.isCompleted) {
                return !connected.isCancelled
            }

            val urlString = webSocketUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://")

            try {
                connection = okHttpClient.newWebSocket(
                    Request.Builder().url(urlString).header(USER_AGENT, USER_AGENT_STRING).build(),
                    this
                ).also {
                    // Preemptively send auth
                    connectionState = WebSocketState.AUTHENTICATING
                    handleAuthComplete(true, null)
                }
            } catch (e: Exception) {
                Timber.e( e, "Unable to connect")
                return false
            }

            // Wait up to 30 seconds for auth response
            return true == withTimeoutOrNull(30000) {
                return@withTimeoutOrNull try {
                    val didConnect = connected.await()
                    didConnect
                } catch (e: Exception) {
                    Timber.e(e, "Unable to authenticate")
                    false
                }
            }
        }
    }

    private suspend fun sendMessage(request: Map<*, *>): SocketResponse? =
        sendMessage(WebSocketRequest(request))

    private suspend fun sendMessage(request: WebSocketRequest, overrideMessageId: String? = null): SocketResponse? {
        return if (connect()) {
            withTimeoutOrNull(request.timeout) {
                try {
                    suspendCancellableCoroutine { cont ->
                        // Lock on the connection so that we fully send before allowing another send.
                        // This should prevent out of order errors.
                        connection?.let {
                            synchronized(it) {
                                val requestId = overrideMessageId ?: id.getAndIncrement().toString()

                                val outbound = request.message.plus("message_id" to requestId)
                                Timber.d("Sending message $requestId: $outbound")
                                activeMessages[requestId] = request.apply {
                                    onResponse = cont
                                }
                                connection?.send(mapper.writeValueAsString(outbound))
                                Timber.d("Message number $requestId sent")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception while sending message")
                    null
                }
            }
        } else {
            Timber.w("Unable to send message, not connected: $request")
            null
        }
    }

    private suspend fun sendBytes(data: ByteArray): Boolean? {
        return if (connect()) {
            withTimeoutOrNull(30_000) {
                try {
                    connection?.let {
                        synchronized(it) {
                            it.send(data.toByteString())
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Exception while sending bytes")
                    null
                }
            }
        } else {
            Timber.w("Unable to send bytes, not connected")
            null
        }
    }

    private inline fun <reified T> mapResponse(response: SocketResponse?): T? =
        if (response?.result != null) mapper.convertValue(response.result) else null

    private fun handleAuthComplete(successful: Boolean, haVersion: String?) {
        if (successful) {
            connectionState = WebSocketState.ACTIVE
            connected.complete(true)
        } else {
            connectionState = WebSocketState.CLOSED_AUTH
            connected.completeExceptionally(AuthorizationException())
        }
    }

    private fun handleMessage(response: SocketResponse) {
        val id = response.messageId!!
        activeMessages[id]?.let {
            it.onResponse?.let { cont ->
                if (cont.isActive) cont.resumeWith(Result.success(response))
            }
            if (it.eventFlow == null) {
                activeMessages.remove(id)
            }
        }
    }

    override fun shutdown() {
        connection?.close(1001, "Session removed from app.")
    }

    private fun handleClosingSocket() {
        ioScope.launch {
            connectedMutex.withLock {
                connected = CompletableDeferred()
                connection = null
                if (connectionState != WebSocketState.CLOSED_AUTH) {
                    connectionState = WebSocketState.CLOSED_OTHER
                }
                synchronized(activeMessages) {
                    activeMessages
                        .filterValues { it.eventFlow == null }
                        .forEach {
                            it.value.onResponse?.let { cont ->
                                if (cont.isActive) cont.resumeWithException(IOException())
                            }
                            activeMessages.remove(it.key)
                        }
                }
            }
        }
        // If we still have flows flowing
        val hasFlowMessages = synchronized(activeMessages) {
            activeMessages.any { it.value.eventFlow != null }
        }
        if (hasFlowMessages && ioScope.isActive) {
            ioScope.launch {
                delay(10000)
                if (connect()) {
                    Timber.d("Resubscribing to active subscriptions...")
                    synchronized(activeMessages) {
                        activeMessages.filterValues { it.eventFlow != null }.entries
                    }.forEach { (oldId, oldMessage) ->
                        val response = sendMessage(oldMessage)
                        if (response?.result == null) {
                            Timber.e("Issue re-registering subscription with " + oldMessage.message)
                        } else {
                            // sendMessage will have created a new item for this subscription
                            activeMessages.remove(oldId)
                        }
                    }
                }
            }
        }
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Timber.d("Websocket: onOpen")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Timber.d("Websocket: onMessage (text: $text)")

        val textTree = mapper.readTree(text)
        val messages: List<SocketResponse> = if (textTree.isArray) {
            textTree.elements().asSequence().toList().map { mapper.convertValue(it) }
        } else {
            listOf(mapper.readValue(text))
        }

        messages.groupBy { it.messageId }.values.forEach { messagesForId ->
            ioScope.launch {
                messagesForId.forEach { message ->
                    Timber.d("Message number " + message.messageId + " received: " + message)
                    if (message.result != null || message.errorCode != null || message.messageId == "remove_node") {
                        handleMessage(message)
                    } else {
                        Timber.d("Unknown message type")
                    }

                }
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Timber.d("Websocket: onMessage (bytes)")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Timber.d("Websocket: onClosing code: $code, reason: $reason")
        handleClosingSocket()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Timber.d("Websocket: onClosed")
        handleClosingSocket()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Timber.e(t, "Websocket: onFailure")
        if (connected.isActive) {
            connected.completeExceptionally(t)
        }
        handleClosingSocket()
    }
}
