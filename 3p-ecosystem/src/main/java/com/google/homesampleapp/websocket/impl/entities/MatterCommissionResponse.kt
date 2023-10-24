package com.google.homesampleapp.websocket.impl.entities

data class MatterCommissionResponse(
    val success: Boolean,
    val nodeId: Int?,
    val errorCode: Int?,
    val details: String? = null
)
