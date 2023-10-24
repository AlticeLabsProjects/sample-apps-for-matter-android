package com.google.homesampleapp.websocket

import com.google.homesampleapp.websocket.impl.WebSocketRepositoryImpl
import com.google.homesampleapp.websocket.impl.entities.MatterCommissionResponse
import dagger.assisted.AssistedFactory

interface WebSocketRepository {
    fun getWebSocketUrl(): String
    fun getConnectionState(): WebSocketState?
    fun shutdown()

    /**
     * Request the server to add a Matter device to the network and commission it.
     * @return [MatterCommissionResponse] detailing the server's response, or `null` if the server
     * did not return a response.
     */
    suspend fun commissionMatterDevice(code: String): MatterCommissionResponse?

    /**
     * Request the server to commission a Matter device that is already on the network.
     * @return [MatterCommissionResponse] detailing the server's response, or `null` if the server
     * did not return a response.
     */
    suspend fun commissionMatterDeviceOnNetwork(pin: Long): MatterCommissionResponse?

    /**
     * Request the server to remove a device.
     * @return [MatterCommissionResponse] detailing the server's response, or `null` if the server
     * did not return a response.
     */
    suspend fun removeMatterDevice(nodeId: Long): MatterCommissionResponse?

}

@AssistedFactory
interface WebSocketRepositoryFactory {
    fun create(webSocketUrl: String): WebSocketRepositoryImpl
}
