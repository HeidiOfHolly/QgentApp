package com.example.qgent.ui.delivery

/**
 * 交付确认/拒绝权限：仅任务创建者（createdByUser）或项目管理员（Project Admin）可操作。
 * 客户端自判（拉任务详情取创建者 + 校验管理员），不再依赖后端能力位兜底。
 */
object DeliveryPermission {

    /** 是否可对某任务执行交付确认/拒绝：管理员恒可；否则要求当前用户是任务创建者 */
    fun canDecide(currentUserId: String?, creatorId: String?, isAdmin: Boolean): Boolean =
        isAdmin || (currentUserId != null && currentUserId == creatorId)
}
