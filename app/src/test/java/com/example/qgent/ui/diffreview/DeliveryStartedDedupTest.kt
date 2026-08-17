package com.example.qgent.ui.diffreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * delivery.started 事件去重单测：重复 / 乱序 / 晚到事件以 taskId + operationId 去重，
 * 断线重连后以查询接口为准，不依赖本地事件缓存。
 */
class DeliveryStartedDedupTest {

    @Test
    fun key_requiresTaskIdAndOperationId() {
        assertEquals("task-1:op-1", DiffReviewRules.deliveryStartedKey("task-1", "op-1"))
        assertNull(DiffReviewRules.deliveryStartedKey(null, "op-1"))
        assertNull(DiffReviewRules.deliveryStartedKey("task-1", null))
        assertNull(DiffReviewRules.deliveryStartedKey("", "op-1"))
        assertNull(DiffReviewRules.deliveryStartedKey("task-1", "  "))
    }

    @Test
    fun keyFromPayload_parsesFields() {
        val payload = """{"projectId":"p1","taskId":"task-1","reviewBatchId":"batch-1",
            "deliveryMode":"MR_FIRST","operationId":"op-1","reason":"规则命中自动交付"}"""
        assertEquals("task-1:op-1", DiffReviewRules.deliveryStartedKeyFromPayload(payload))
        // 只有 taskId：解析不到 key（不参与去重，按一次处理）
        assertNull(DiffReviewRules.deliveryStartedKeyFromPayload("""{"taskId":"task-1"}"""))
        assertNull(DiffReviewRules.deliveryStartedKeyFromPayload("""{"operationId":"op-1"}"""))
        // 非法 JSON / 空 payload
        assertNull(DiffReviewRules.deliveryStartedKeyFromPayload("not-json"))
        assertNull(DiffReviewRules.deliveryStartedKeyFromPayload(""))
        assertNull(DiffReviewRules.deliveryStartedKeyFromPayload(null))
    }

    @Test
    fun duplicateOrLateEvents_deduplicatedByKey() {
        val seen = mutableSetOf<String>()
        // 同一 taskId+operationId 的重复事件：第二次不再触发刷新
        val key1 = DiffReviewRules.deliveryStartedKeyFromPayload(
            """{"taskId":"task-1","operationId":"op-1"}"""
        )
        assertTrue(key1 != null && seen.add(key1))
        assertTrue(!seen.add(key1!!))
        // 乱序晚到的不同 operationId：视为新事件，允许刷新（状态以查询接口为准）
        val key2 = DiffReviewRules.deliveryStartedKeyFromPayload(
            """{"taskId":"task-1","operationId":"op-2"}"""
        )
        assertTrue(key2 != null && seen.add(key2))
    }

    @Test
    fun reconnect_reliesOnQueryNotLocalCache() {
        // 断线重连后客户端只维护去重集合，业务状态一律重新查询——
        // 去重集合仅影响“是否重复刷新”，不影响“刷新出来的数据正确性”
        val seen = mutableSetOf<String>()
        val reconnectEvent = """{"taskId":"task-9","operationId":"op-9"}"""
        assertTrue(DiffReviewRules.deliveryStartedKeyFromPayload(reconnectEvent)?.let { seen.add(it) } == true)
        // 重连重放同一事件：应被去重（避免重复请求风暴）
        assertTrue(DiffReviewRules.deliveryStartedKeyFromPayload(reconnectEvent)?.let { seen.add(it) } == false)
    }
}
