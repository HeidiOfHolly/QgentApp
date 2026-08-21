package com.example.qgent.data.model

import com.example.qgent.model.Agent
import com.example.qgent.model.AgentRole
import com.example.qgent.model.AgentStatus
import com.example.qgent.model.AgentVisibility
import com.example.qgent.model.ChatMessage
import com.example.qgent.model.DiffFile
import com.example.qgent.model.DiffLine
import com.example.qgent.model.DiffLineType
import com.example.qgent.model.GroupMember
import com.example.qgent.model.MemberType
import com.example.qgent.model.MessageType
import com.example.qgent.model.TaskStepSnapshot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** AgentDto → UI Agent。description 后端返回（v2.0.4 起），缺失时用能力标签拼接兜底。
 *  role 枚举只收敛 4 种执行角色，ORCHESTRATOR/GENERAL 等兜底 DEVELOPER；roleWire 保留后端原值供展示。 */
fun AgentDto.toAgent(): Agent = Agent(
    id = id,
    name = name,
    description = description?.takeIf { it.isNotBlank() } ?: capabilities?.joinToString(", ").orEmpty(),
    role = runCatching { AgentRole.valueOf(role) }.getOrDefault(AgentRole.DEVELOPER),
    roleWire = role,
    capabilities = capabilities ?: emptyList(),
    status = if (status == "ARCHIVED") AgentStatus.ARCHIVED else AgentStatus.ACTIVE,
    visibility = when (visibility) {
        "PRIVATE" -> AgentVisibility.PRIVATE
        "PENDING" -> AgentVisibility.PENDING
        "TEAM", "TEAM_SHARED" -> AgentVisibility.TEAM
        else -> AgentVisibility.TEAM
    },
    avatar = avatar,
    createdBy = createdBy
)

/** GroupMemberDto → UI GroupMember。memberType=AGENT 映射为 AGENT，其余按 HUMAN */
fun GroupMemberDto.toGroupMember(): GroupMember = GroupMember(
    id = id,
    name = resolvedName,
    type = if (isAgent) MemberType.AGENT else MemberType.HUMAN,
    avatar = avatar
)

/** GroupMessageDto → UI ChatMessage。isMine 依据当前用户 id 与 senderId 比对。
 *  TASK_STATUS 消息 content 无 text，从 JSON 解析 taskId/status/node/message 拼可读摘要。 */
fun GroupMessageDto.toChatMessage(myUserId: String?, memberNamesById: Map<String, String>): ChatMessage {
    val parsedType = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT)
    android.util.Log.d("MsgRaw", "type=$type senderType=$senderType senderId=$senderId seq=$sequence replyText=$replyText mentions=$mentions content=${content?.let { com.google.gson.Gson().toJson(it) }}")
    val displayContent = when {
        parsedType == MessageType.IMAGE || parsedType == MessageType.FILE -> content?.url ?: ""
        parsedType == MessageType.TASK_STATUS -> taskStatusSummary()
        // v2.0.6 §1.4：QUOTE 回复正文 = content.replyText（旧版）或顶层 replyText（新版）或 content.text（更旧）
        parsedType == MessageType.QUOTE -> content?.replyText ?: replyText ?: content?.text ?: ""
        else -> content?.text ?: ""
    }
    // v2.0.4：QUOTE 消息的引用摘要由后端直接给出（quotedSenderName + quotedText），
    // 优于本地按 replyToId 反查（反查不到时为 null，交由调用方兜底）
    val quoteSummary = if (parsedType == MessageType.QUOTE) {
        val qText = content?.quotedText
        val qSender = content?.quotedSenderName
        when {
            !qText.isNullOrBlank() && !qSender.isNullOrBlank() -> "$qSender：$qText"
            !qText.isNullOrBlank() -> qText
            else -> null
        }
    } else {
        null
    }
    return ChatMessage(
        id = id,
        senderId = senderId,
        // v1.6.0 起后端返回 senderName（用户=displayName、Agent=name、SYSTEM=null），反查仅兜底
        senderName = senderName?.takeIf { it.isNotBlank() } ?: memberNamesById[senderId] ?: "成员",
        content = displayContent,
        type = parsedType,
        timestamp = parseRfc3339(createdAt),
        isMine = myUserId != null && senderId == myUserId,
        fileName = content?.name,
        fileSize = content?.size,
        sequence = sequence,
        replyToId = replyToId,
        replyToSummary = quoteSummary,
        senderType = senderType,
        taskStatus = if (parsedType == MessageType.TASK_STATUS) content?.status else null,
        taskNode = if (parsedType == MessageType.TASK_STATUS) content?.node else null,
        taskId = if (parsedType == MessageType.TASK_STATUS || parsedType == MessageType.DIFF) content?.taskId else null,
        // v23：TASK_STATUS 卡单消息持续更新，新增 phase / deliveryMode / plan 快照
        taskPhase = if (parsedType == MessageType.TASK_STATUS) content?.phase else null,
        taskDeliveryMode = if (parsedType == MessageType.TASK_STATUS) content?.deliveryMode else null,
        taskPlanSummary = if (parsedType == MessageType.TASK_STATUS) content?.plan?.summary else null,
        taskPlanSteps = if (parsedType == MessageType.TASK_STATUS) content?.plan?.steps?.map { it.toTaskStepSnapshot() } else null,
        diffId = if (parsedType == MessageType.DIFF) content?.diffId else null,
        diffTitle = if (parsedType == MessageType.DIFF) content?.title else null,
        diffAdditions = if (parsedType == MessageType.DIFF) content?.additions else null,
        diffDeletions = if (parsedType == MessageType.DIFF) content?.deletions else null,
        reviewBatchId = if (parsedType == MessageType.DIFF) content?.reviewBatchId else null,
        reviewStatus = if (parsedType == MessageType.DIFF) content?.reviewStatus else null,
        deliveryStatus = if (parsedType == MessageType.DIFF) content?.deliveryStatus else null,
        // §7.1 MESSAGE_MENTION 通知直达：记录 @ 提及 id，resourceId 缺失时兜底定位「最上面一条被 @ 的消息」
        mentionIds = mentions?.mapNotNull { it.id },
        // 幂等键回显：重发时复用同一 clientMessageId，后端按幂等返回原消息
        clientMessageId = clientMessageId,
        // 附件内联预览（契约 v0.1）：IMAGE/FILE 消息的 attachmentId 必填；预览字段由后端回填（§7）或前端按需调 preview-url
        attachmentId = if (parsedType == MessageType.IMAGE || parsedType == MessageType.FILE) content?.attachmentId else null,
        previewable = content?.previewable,
        previewType = if (parsedType == MessageType.IMAGE || parsedType == MessageType.FILE) content?.previewType else null,
        previewUrl = if (parsedType == MessageType.IMAGE || parsedType == MessageType.FILE) content?.previewUrl else null,
        downloadUrl = if (parsedType == MessageType.IMAGE || parsedType == MessageType.FILE) content?.downloadUrl else null
    )
}

/** v23：TaskStepSnapshotDto → UI TaskStepSnapshot */
private fun TaskStepSnapshotDto.toTaskStepSnapshot(): TaskStepSnapshot = TaskStepSnapshot(
    stepId = stepId,
    sequence = sequence,
    title = title,
    role = role,
    status = status,
    message = message
)

/** TASK_STATUS 卡片摘要：content JSON 含 taskId/status/node/message，拼成「状态 · 节点 · 说明」 */
private fun GroupMessageDto.taskStatusSummary(): String {
    val c = content ?: return "[任务状态]"
    val statusLabel = c.status ?: c.text ?: return "[任务状态]"
    return buildString {
        append("任务状态：").append(statusLabel)
        c.node?.let { append(" · ").append(it) }
        c.message?.let { append("\n").append(it) }
    }
}

/** DiffFileDto → UI DiffFile（hunks 或 lines 两种形态都支持，行扁平化为 DiffLine） */
fun DiffFileDto.toDiffFile(): DiffFile = DiffFile(
    fileName = (path ?: fileName) ?: "未知文件",
    additions = additions,
    deletions = deletions,
    lines = toDiffLines()
)

private fun DiffFileDto.toDiffLines(): List<DiffLine> {
    val fromHunks = hunks.orEmpty().flatMap { hunk ->
        hunk.lines.orEmpty().map { it.toDiffLine() }
    }
    if (fromHunks.isNotEmpty()) return fromHunks
    return lines.orEmpty().map { it.toDiffLine() }
}

private fun DiffHunkLineDto.toDiffLine(): DiffLine = DiffLine(
    type = runCatching { DiffLineType.valueOf(type ?: "CONTEXT") }.getOrDefault(DiffLineType.CONTEXT),
    oldLineNo = oldLineNo,
    newLineNo = newLineNo,
    // text 后端可能为 null（Gson 绕过空安全），兜底空串避免 NPE
    text = text ?: ""
)

/** 群列表摘要：图片/文件消息显示 [图片]/[文件]，DIFF 卡显示 [Diff 待验收]；senderName 为空（如 SYSTEM 消息）时只显示正文。 */
fun GroupLatestMessageDto?.toSummary(): String {
    if (this == null) return ""
    val body = when (type) {
        "IMAGE" -> "[图片]"
        "FILE" -> "[文件]"
        "DIFF" -> "[Diff 待验收]"
        else -> text ?: ""
    }
    return if (senderName.isNullOrBlank()) body else "$senderName：$body"
}

/** 解析 RFC3339 时间到 epoch 毫秒，失败回退当前时间。
 *  兼容带小数秒（.123）与 Z / ±HH:MM 时区后缀；无时区后缀按 UTC 兜底。
 *  此前 substringBefore('.') 会把小数秒连同末尾的 Z 一起截掉，导致带小数秒的 UTC 时间
 *  被误按本地时区解析，整体偏移一个时区（东八区即 8 小时），进而红点误判。 */
fun parseRfc3339(value: String): Long {
    val s = value.trim().replace(Regex("\\.\\d+"), "")
    return runCatching {
        if (s.endsWith("Z", ignoreCase = true) || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(s)) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)?.time
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(s)?.time
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

/** 完整本地时间（执行日志等明细场景）：MM-dd HH:mm:ss；解析失败回退空串。 */
fun formatFullTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
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
