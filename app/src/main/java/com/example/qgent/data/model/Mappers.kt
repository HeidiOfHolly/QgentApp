package com.example.qgent.data.model

import com.example.qgent.model.Agent
import com.example.qgent.model.AgentRole
import com.example.qgent.model.AgentStatus
import com.example.qgent.model.AgentVisibility
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** AgentDto → UI Agent。API 无 description 字段，置空。 */
fun AgentDto.toAgent(): Agent = Agent(
    id = id,
    name = name,
    description = "",
    role = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.GENERAL),
    capabilities = capabilities ?: emptyList(),
    status = if (status == "ARCHIVED") AgentStatus.ARCHIVED else AgentStatus.ACTIVE,
    visibility = if (visibility == "PRIVATE") AgentVisibility.PRIVATE else AgentVisibility.TEAM_SHARED
)

/** GroupMemberDto → UI GroupMember。API 无 member/agent 类型字段，统一按 HUMAN。 */
fun GroupMemberDto.toGroupMember(): GroupMember = GroupMember(
    name = nickname,
    type = MemberType.HUMAN
)

/** GroupMessageDto → UI ChatMessage。isMine 依据当前用户 id 与 senderId 比对。 */
fun GroupMessageDto.toChatMessage(myUserId: String?): ChatMessage {
    val parsedType = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT)
    return ChatMessage(
        id = id,
        senderName = senderName,
        content = content?.text ?: "",
        type = parsedType,
        timestamp = parseRfc3339(createdAt),
        isMine = myUserId != null && senderId == myUserId
    )
}

/** 解析 UTC RFC3339 时间到 epoch 毫秒，失败回退当前时间。 */
private fun parseRfc3339(value: String): Long {
    val cleaned = value.trim().substringBefore('.')
    return runCatching {
        if (cleaned.endsWith("Z")) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(cleaned.dropLast(1))?.time
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(cleaned)?.time
        }
    }.getOrNull() ?: System.currentTimeMillis()
}
