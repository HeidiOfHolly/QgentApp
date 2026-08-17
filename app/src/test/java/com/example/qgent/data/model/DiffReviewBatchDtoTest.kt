package com.example.qgent.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiffReviewBatchDto（MR_FIRST B 方案）Gson 解析单测：
 * confirmationSource / repositoryDeliveries 字段解析；mergeRequest.webUrl 为空时不渲染链接（由规则层兜底）。
 */
class DiffReviewBatchDtoTest {

    private val gson = Gson()

    @Test
    fun parsesMrFirstAutoDeliveringBatch() {
        val json = """
            {
              "id": "batch-1",
              "taskId": "task-1",
              "reviewStatus": "ACCEPTED",
              "deliveryStatus": "DELIVERING",
              "confirmationSource": "SYSTEM",
              "repositoryCount": 2,
              "repositoryDeliveries": [
                {"repositoryId": "repo-1", "repositoryName": "qgents-web", "deliveryStatus": "COMMITTED"},
                {"repositoryId": "repo-2", "repositoryName": "qgents-mobile", "deliveryStatus": "NOT_STARTED"}
              ]
            }
        """.trimIndent()

        val batch = gson.fromJson(json, DiffReviewBatchDto::class.java)
        assertEquals("ACCEPTED", batch.reviewStatus)
        assertEquals("DELIVERING", batch.deliveryStatus)
        assertEquals("SYSTEM", batch.confirmationSource)
        assertEquals(2, batch.repositoryDeliveries?.size)
        assertEquals("COMMITTED", batch.repositoryDeliveries?.get(0)?.deliveryStatus)
        assertEquals("qgents-web", batch.repositoryDeliveries?.get(0)?.repositoryName)
    }

    @Test
    fun parsesMrCreatedWithWebUrl() {
        val json = """
            {
              "id": "batch-1",
              "confirmationSource": "SYSTEM",
              "deliveryStatus": "PARTIALLY_DELIVERED",
              "repositoryDeliveries": [
                {
                  "repositoryId": "repo-1",
                  "repositoryName": "qgents-web",
                  "deliveryStatus": "MR_CREATED",
                  "mergeRequest": {"webUrl": "https://github.com/x/y/pull/42", "number": 42, "title": "feat: 邮箱登录"},
                  "updatedAt": "2026-08-19T10:05:00Z"
                },
                {
                  "repositoryId": "repo-2",
                  "repositoryName": "qgents-mobile",
                  "deliveryStatus": "FAILED",
                  "failureCode": "GIT_PUSH_REJECTED",
                  "failureReason": "推送被拒绝：token 无效"
                }
              ]
            }
        """.trimIndent()

        val batch = gson.fromJson(json, DiffReviewBatchDto::class.java)
        val deliveries = batch.repositoryDeliveries.orEmpty()
        assertEquals(2, deliveries.size)
        val ok = deliveries[0]
        assertEquals("MR_CREATED", ok.deliveryStatus)
        assertEquals("https://github.com/x/y/pull/42", ok.mergeRequest?.webUrl)
        assertEquals(42, ok.mergeRequest?.number)
        val failed = deliveries[1]
        assertEquals("FAILED", failed.deliveryStatus)
        assertEquals("GIT_PUSH_REJECTED", failed.failureCode)
        assertEquals("推送被拒绝：token 无效", failed.failureReason)
    }

    @Test
    fun webUrlNull_parsesButLinkRenderingDisabledByRules() {
        val json = """
            {
              "id": "batch-1",
              "confirmationSource": "SYSTEM",
              "repositoryDeliveries": [
                {"repositoryId": "repo-1", "deliveryStatus": "MR_CREATED", "mergeRequest": {"number": 42}}
              ]
            }
        """.trimIndent()

        val batch = gson.fromJson(json, DiffReviewBatchDto::class.java)
        val mr = batch.repositoryDeliveries?.firstOrNull()?.mergeRequest
        assertNull(mr?.webUrl)
        assertEquals(42, mr?.number)
        // 渲染规则：webUrl 为空 → 不渲染链接（DiffReviewRules.mrLinkText 返回 null）
        assertNull(com.example.qgent.ui.diffreview.DiffReviewRules.mrLinkText(mr))
    }

    @Test
    fun missingFields_defaultToNull() {
        val json = """{"id": "batch-1"}"""
        val batch = gson.fromJson(json, DiffReviewBatchDto::class.java)
        assertNull(batch.reviewStatus)
        assertNull(batch.deliveryStatus)
        assertNull(batch.confirmationSource)
        assertTrue(batch.repositoryDeliveries.isNullOrEmpty())
        assertEquals(0, batch.repositoryCount)
    }
}
