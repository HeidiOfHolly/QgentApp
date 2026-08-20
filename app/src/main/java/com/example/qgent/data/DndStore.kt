package com.example.qgent.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 群消息免打扰（v1 本地实现，仅本机生效）。
 *
 * 免打扰语义（微信式）：该群消息在后台不弹系统通知，未读红点/角标照常累计；
 * 前台界面本就不弹通知（isAppForeground），故本存储只影响后台广播（QgentApp.notifyMessageCreated）。
 *
 * 多端同步预留：后续后端提供用户偏好接口（免打扰群 id 集合读写）后，
 * 只需把本类的读写换成「后端接口 + 本地缓存」，弹通知拦截点（QgentApp）与
 * 群设置页开关无需改动。
 */
object DndStore {

    private const val PREFS_NAME = "qgent_dnd"
    private const val KEY_MUTED_GROUPS = "muted_groups"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("DndStore 未初始化，请先在 Application.onCreate 调用 init")

    /** 该群是否免打扰（后台不弹系统通知） */
    fun isMuted(groupId: String): Boolean =
        groupId.isNotEmpty() && mutedGroups().contains(groupId)

    /** 设置/取消该群免打扰 */
    fun setMuted(groupId: String, muted: Boolean) {
        if (groupId.isEmpty()) return
        val set = mutedGroups().toMutableSet()
        if (muted) set.add(groupId) else set.remove(groupId)
        requirePrefs().edit().putStringSet(KEY_MUTED_GROUPS, set).apply()
    }

    private fun mutedGroups(): Set<String> =
        requirePrefs().getStringSet(KEY_MUTED_GROUPS, emptySet()) ?: emptySet()
}
