package com.example.qgent.data.local

import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType
import com.example.qgent.model.TaskStepSnapshot

class MessageCache(private val dao: MessageDao) {

    suspend fun load(groupId: String): List<ChatMessage> =
        dao.getByGroup(groupId).map { it.toChatMessage() }

    suspend fun save(groupId: String, messages: List<ChatMessage>) {
        dao.clearByGroup(groupId)
        dao.insertAll(messages.map { it.toEntity(groupId) })
    }

    private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        senderId = senderId,
        senderName = senderName,
        content = content,
        type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT),
        timestamp = timestamp,
        isMine = isMine,
        fileName = fileName,
        fileSize = fileSize,
        sequence = sequence,
        replyToId = replyToId,
        replyToSummary = replyToSummary,
        senderType = senderType,
        taskId = taskId,
        taskStatus = taskStatus,
        taskNode = taskNode,
        taskPhase = taskPhase,
        taskDeliveryMode = taskDeliveryMode,
        taskPlanSummary = taskPlanSummary,
        taskPlanSteps = runCatching {
            taskPlanStepsJson?.let { gson.fromJson(it, Array<TaskStepSnapshot>::class.java)?.toList() }
        }.getOrNull(),
        diffId = diffId,
        diffTitle = diffTitle,
        diffAdditions = diffAdditions,
        diffDeletions = diffDeletions,
        reviewBatchId = reviewBatchId,
        reviewStatus = reviewStatus,
        deliveryStatus = deliveryStatus
    )

    private fun ChatMessage.toEntity(groupId: String): MessageEntity = MessageEntity(
        id = id,
        groupId = groupId,
        senderId = senderId,
        senderName = senderName,
        content = content,
        type = type.name,
        timestamp = timestamp,
        isMine = isMine,
        fileName = fileName,
        fileSize = fileSize,
        sequence = sequence,
        replyToId = replyToId,
        replyToSummary = replyToSummary,
        senderType = senderType,
        taskId = taskId,
        taskStatus = taskStatus,
        taskNode = taskNode,
        taskPhase = taskPhase,
        taskDeliveryMode = taskDeliveryMode,
        taskPlanSummary = taskPlanSummary,
        taskPlanStepsJson = taskPlanSteps?.let { gson.toJson(it) },
        diffId = diffId,
        diffTitle = diffTitle,
        diffAdditions = diffAdditions,
        diffDeletions = diffDeletions,
        reviewBatchId = reviewBatchId,
        reviewStatus = reviewStatus,
        deliveryStatus = deliveryStatus
    )

    companion object {
        private val gson = com.google.gson.Gson()
    }
}
