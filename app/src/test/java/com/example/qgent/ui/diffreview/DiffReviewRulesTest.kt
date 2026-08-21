package com.example.qgent.ui.diffreview

import com.example.qgent.data.model.RepositoryDeliveryMergeRequestDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮权限规则单测（MR_FIRST B 方案，§v1.10.0）：
 * - SYSTEM 不显示确认/拒绝；失败/部分失败才显示重试；
 * - webUrl 为空不渲染 MR 链接；DIFF_FIRST 原流程（confirmationSource 缺省）回归不受影响。
 */
class DiffReviewRulesTest {

    // ── 确认/拒绝按钮 ──

    @Test
    fun confirmReject_onlyWhenPendingAndNotSystem() {
        assertTrue(DiffReviewRules.canConfirmOrReject("PENDING_CONFIRMATION", "USER"))
        // confirmationSource 缺省按 USER 兜底（DIFF_FIRST 旧后端回归）
        assertTrue(DiffReviewRules.canConfirmOrReject("PENDING_CONFIRMATION", null))
        // SYSTEM 自动交付：绝不显示确认/拒绝
        assertFalse(DiffReviewRules.canConfirmOrReject("PENDING_CONFIRMATION", "SYSTEM"))
        // 已确认/已拒绝：不显示
        assertFalse(DiffReviewRules.canConfirmOrReject("ACCEPTED", "USER"))
        assertFalse(DiffReviewRules.canConfirmOrReject("ACCEPTED", "SYSTEM"))
        assertFalse(DiffReviewRules.canConfirmOrReject("REJECTED", "USER"))
        assertFalse(DiffReviewRules.canConfirmOrReject(null, null))
        assertFalse(DiffReviewRules.canConfirmOrReject("", "USER"))
    }

    // ── ACCEPTED 文案 ──

    @Test
    fun acceptedCaption_neverShowsUserConfirmedForSystem() {
        assertEquals("已自动确认", DiffReviewRules.acceptedCaption("SYSTEM"))
        assertEquals("已由用户确认", DiffReviewRules.acceptedCaption("USER"))
        assertEquals("已确认", DiffReviewRules.acceptedCaption(null))
    }

    // ── 重试按钮 ──

    @Test
    fun retry_onlyForPartialOrFailed() {
        assertTrue(DiffReviewRules.canRetryDelivery("PARTIALLY_DELIVERED", "DELIVERING", null))
        assertTrue(DiffReviewRules.canRetryDelivery("FAILED", "DELIVERY_FAILED", null))
        // 旧枚举 DELIVERY_FAILED 兼容
        assertTrue(DiffReviewRules.canRetryDelivery("DELIVERY_FAILED", "DELIVERY_FAILED", null))
        // 任务级 DELIVERY_FAILED 兜底
        assertTrue(DiffReviewRules.canRetryDelivery(null, "DELIVERY_FAILED", null))
        // 能力位优先
        assertTrue(DiffReviewRules.canRetryDelivery("DELIVERED", "SUCCEEDED", true))
        // 正常完成/进行中：不显示重试
        assertFalse(DiffReviewRules.canRetryDelivery("DELIVERED", "SUCCEEDED", null))
        assertFalse(DiffReviewRules.canRetryDelivery("DELIVERING", "DELIVERING", null))
        assertFalse(DiffReviewRules.canRetryDelivery("NOT_STARTED", "RUNNING", null))
        assertFalse(DiffReviewRules.canRetryDelivery(null, "RUNNING", null))
    }

    // ── 稳定文案 ──

    @Test
    fun deliveryStatusCaption_stableText() {
        assertEquals("未开始", DiffReviewRules.deliveryStatusCaption("NOT_STARTED"))
        assertEquals("交付中", DiffReviewRules.deliveryStatusCaption("DELIVERING"))
        assertEquals("交付完成", DiffReviewRules.deliveryStatusCaption("DELIVERED"))
        assertEquals("部分仓库成功，部分仓库失败", DiffReviewRules.deliveryStatusCaption("PARTIALLY_DELIVERED"))
        assertEquals("交付失败", DiffReviewRules.deliveryStatusCaption("FAILED"))
        assertEquals("交付失败", DiffReviewRules.deliveryStatusCaption("DELIVERY_FAILED"))
        assertNull(DiffReviewRules.deliveryStatusCaption(null))
        assertNull(DiffReviewRules.deliveryStatusCaption("PENDING_CONFIRMATION"))
    }

    @Test
    fun repositoryDeliveryCaption_committedIsNotMrCreated() {
        assertEquals("未开始", DiffReviewRules.repositoryDeliveryCaption("NOT_STARTED"))
        assertEquals("已提交代码", DiffReviewRules.repositoryDeliveryCaption("COMMITTED"))
        assertEquals("已创建合并请求", DiffReviewRules.repositoryDeliveryCaption("MR_CREATED"))
        assertEquals("交付失败", DiffReviewRules.repositoryDeliveryCaption("FAILED"))
        assertEquals("未知", DiffReviewRules.repositoryDeliveryCaption(null))
    }

    @Test
    fun deliveryModeCaption() {
        assertEquals("MR 前自动预检", DiffReviewRules.deliveryModeCaption("MR_FIRST"))
        assertEquals("人工确认", DiffReviewRules.deliveryModeCaption("DIFF_FIRST"))
        assertNull(DiffReviewRules.deliveryModeCaption(null))
        assertTrue(DiffReviewRules.isMrFirst("MR_FIRST"))
        assertFalse(DiffReviewRules.isMrFirst("DIFF_FIRST"))
    }

    // ── MR_FIRST 标签展示（仅真正进入交付阶段才展示，避免误解为已自动交付） ──

    @Test
    fun showDeliveryModeLabel_mrFirstHiddenBeforeDelivery() {
        // 进入交付阶段（待 Diff 确认及之后）：展示
        assertTrue(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "WAITING_DIFF_CONFIRMATION"))
        assertTrue(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "DELIVERING"))
        assertTrue(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "SUCCEEDED"))
        assertTrue(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "DELIVERY_FAILED"))
        // 规划/待执行/执行中/失败/取消：deliveryMode 只是规划标签，不展示
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "PLANNING"))
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "PENDING"))
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "RUNNING"))
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "FAILED"))
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "CANCELLING"))
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", "CANCELLED"))
        // 状态缺失按不展示兜底（避免误以为已交付）
        assertFalse(DiffReviewRules.showDeliveryModeLabel("MR_FIRST", null))
        // 非 MR_FIRST：不受状态影响
        assertTrue(DiffReviewRules.showDeliveryModeLabel("DIFF_FIRST", "PLANNING"))
        assertTrue(DiffReviewRules.showDeliveryModeLabel("DIFF_FIRST", "RUNNING"))
        assertTrue(DiffReviewRules.showDeliveryModeLabel(null, "SUCCEEDED"))
    }

    // ── MR 链接：webUrl 为空不渲染 ──

    @Test
    fun mrLink_onlyWhenWebUrlNonBlank() {
        assertNull(DiffReviewRules.mrLinkText(null))
        assertNull(DiffReviewRules.mrLinkText(RepositoryDeliveryMergeRequestDto(webUrl = null, number = 42)))
        assertNull(DiffReviewRules.mrLinkText(RepositoryDeliveryMergeRequestDto(webUrl = "", number = 42)))
        assertEquals(
            "查看合并请求 #42 ↗",
            DiffReviewRules.mrLinkText(RepositoryDeliveryMergeRequestDto(webUrl = "https://github.com/x/y/pull/42", number = 42))
        )
        assertEquals(
            "查看合并请求 ↗",
            DiffReviewRules.mrLinkText(RepositoryDeliveryMergeRequestDto(webUrl = "https://github.com/x/y/pull/42", number = null))
        )
    }

    // ── 409 冲突识别 ──

    @Test
    fun isConflict_detects409AndKnownBusinessCodes() {
        assertTrue(DiffReviewRules.isConflict("HTTP_409"))
        assertTrue(DiffReviewRules.isConflict("DIFF_BATCH_REVIEW_REQUIRED"))
        assertTrue(DiffReviewRules.isConflict("DIFF_REVIEW_STATE_CONFLICT"))
        assertTrue(DiffReviewRules.isConflict("DELIVERY_STATE_CONFLICT"))
        assertFalse(DiffReviewRules.isConflict("HTTP_500"))
        assertFalse(DiffReviewRules.isConflict("GIT_PUSH_REJECTED"))
        assertFalse(DiffReviewRules.isConflict(null))
    }
}
