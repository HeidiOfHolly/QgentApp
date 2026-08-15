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
import java.util.Calendar
import java.util.Date
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
    name = resolvedName,
    type = MemberType.HUMAN
)

/** GroupMessageDto → UI ChatMessage。isMine 依据当前用户 id 与 senderId 比对。 */
fun GroupMessageDto.toChatMessage(myUserId: String?, memberNamesById: Map<String, String>): ChatMessage {
    val parsedType = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT)
    val displayContent = if (parsedType == MessageType.IMAGE || parsedType == MessageType.FILE) {
        content?.url ?: ""
    } else {
        content?.text ?: ""
    }
    return ChatMessage(
        id = id,
        // v1.6.0 起后端返回 senderName（用户=displayName、Agent=name、SYSTEM=null），反查仅兜底
        senderName = senderName?.takeIf { it.isNotBlank() } ?: memberNamesById[senderId] ?: "成员",
        content = displayContent,
        type = parsedType,
        timestamp = parseRfc3339(createdAt),
        isMine = myUserId != null && senderId == myUserId,
        fileName = content?.name,
        fileSize = content?.size
    )
}

/** 群列表摘要：图片/文件消息显示 [图片]/[文件]，其余显示 text；senderName 为空（如 SYSTEM 消息）时只显示正文。 */
fun GroupLatestMessageDto?.toSummary(): String {
    if (this == null) return ""
    val body = when (type) {
        "IMAGE" -> "[图片]"
        "FILE" -> "[文件]"
        else -> text ?: ""
    }
    return if (senderName.isNullOrBlank()) body else "$senderName：$body"
}

/** 解析 UTC RFC3339 时间到 epoch 毫秒，失败回退当前时间。 */
fun parseRfc3339(value: String): Long {
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

/** 群列表时间标签：当天 HH:mm，昨天「昨天」，近 7 天显示星期几，更早 MM-dd。 */
fun formatGroupTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val diffDays = dayDiff(timestamp, System.currentTimeMillis())
    return when {
        diffDays == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        diffDays == 1 -> "昨天"
        diffDays in 2..7 -> WEEK_LABELS[
            Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.DAY_OF_WEEK)
        ]
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }
}

private val WEEK_LABELS = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")

private fun dayDiff(from: Long, to: Long): Int {
    val start = Calendar.getInstance().apply {
        timeInMillis = from
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val end = Calendar.getInstance().apply {
        timeInMillis = to
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return ((end.timeInMillis - start.timeInMillis) / (24 * 60 * 60 * 1000)).toInt()
}
