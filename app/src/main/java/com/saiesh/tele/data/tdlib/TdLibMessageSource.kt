package com.saiesh.tele.data.tdlib

import com.saiesh.tele.core.tdlib.TdLibClient
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TdLibMessageSource(
    private val client: Client = TdLibClient.client,
) {
    fun deleteMessages(
        chatId: Long,
        messageIds: LongArray,
        onResult: (String?) -> Unit,
    ) {
        client.send(TdApi.DeleteMessages(chatId, messageIds, false)) { result ->
            when (result) {
                is TdApi.Ok -> onResult(null)
                is TdApi.Error -> onResult(result.message)
                else -> onResult("Failed to delete message")
            }
        }
    }
}
