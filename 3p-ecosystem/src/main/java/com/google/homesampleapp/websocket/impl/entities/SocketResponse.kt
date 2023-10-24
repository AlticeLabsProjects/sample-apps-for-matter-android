package com.google.homesampleapp.websocket.impl.entities

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

@JsonIgnoreProperties(ignoreUnknown = true)
data class SocketResponse(
    @JsonAlias("message_id")
    val messageId: String?,
    @JsonAlias("result")
    val result: JsonNode?,
    @JsonAlias("error_code")
    val errorCode: Int?,
    @JsonAlias("details")
    val details: String?
)
