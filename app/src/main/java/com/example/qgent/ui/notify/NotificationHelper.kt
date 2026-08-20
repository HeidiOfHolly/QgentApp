package com.example.qgent.ui.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.qgent.MainActivity
import com.example.qgent.R

/**
 * 群消息系统通知（档位 1 简化版：WS 常驻连接，后台收到 message.created 事件驱动弹通知）。
 * - 点击通知 → MainActivity（extras: groupId/groupName）→ 跳进对应群聊；
 * - 弹不弹由 [isAppForeground]（QgentApp 生命周期维护）决定：前台不弹（界面已刷新），后台才弹。
 */
object NotificationHelper {

    const val CHANNEL_CHAT = "chat_messages"

    /** App 是否在前台（QgentApp 的 Activity 生命周期计数维护） */
    @Volatile
    var isAppForeground: Boolean = true

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_CHAT, "群聊消息", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "群聊新消息、@我 提醒" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 跳转系统「应用通知设置」页（权限未开时引导） */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            }
    }

    /** 弹一条群消息通知；微信式：未读数（>1）显示在发送者名字前（[N条] 发送者：内容）；@我时加「@你：」；点击进群。
     *  [largeIcon] 通知横幅/卡片右侧图标：群聊头像拼图（成员头像拼，由调用方生成），null 时兜底品牌 Logo */
    fun showChatNotification(
        context: Context,
        projectId: String,
        groupId: String,
        groupName: String,
        body: String,
        mentioned: Boolean,
        unreadCount: Int? = null,
        largeIcon: android.graphics.Bitmap? = null
    ) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        // 未读数放正文最前：`[3条] 发送者：内容`（微信式；副标题方式部分系统不显示，改用正文）
        val unreadPrefix = if (unreadCount != null && unreadCount > 1) "[${unreadCount}条] " else ""
        val text = unreadPrefix + (if (mentioned) "@你：$body" else body)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // projectId 用于点击后先把团队/项目切到该消息所属项目，再进对应群聊（群消息按项目隔离）
            putExtra("projectId", projectId)
            putExtra("groupId", groupId)
            putExtra("groupName", groupName)
        }
        val pending = PendingIntent.getActivity(
            context, groupId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(if (mentioned) "有人@你" else groupName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        // 右侧图标：优先群聊头像拼图，缺省兜底品牌 Logo（adaptive icon 在横幅里缩成小缩略图像默认）
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        } else {
            builder.setLargeIcon(
                android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_launcher_foreground_img)
            )
        }
        val notification = builder.build()
        // 同群固定 ID：同一群新消息更新同一条；不同群各自独立
        NotificationManagerCompat.from(context).notify(1000 + groupId.hashCode(), notification)
    }
}
