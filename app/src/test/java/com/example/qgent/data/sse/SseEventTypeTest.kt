package com.example.qgent.data.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SSE 事件类型与 wire 名映射（含 MR_FIRST B 方案新增 delivery.started） */
class SseEventTypeTest {

    @Test
    fun deliveryStarted_wireName() {
        assertEquals("delivery.started", SseEventType.DELIVERY_STARTED.wire)
        assertEquals(SseEventType.DELIVERY_STARTED, SseEventType.fromWire("delivery.started"))
    }

    @Test
    fun deliveryEvents_allRegistered() {
        assertEquals(SseEventType.DELIVERY_REPOSITORY_UPDATED, SseEventType.fromWire("delivery.repository.updated"))
        assertEquals(SseEventType.DELIVERY_COMPLETED, SseEventType.fromWire("delivery.completed"))
        assertEquals(SseEventType.DELIVERY_FAILED, SseEventType.fromWire("delivery.failed"))
    }

    @Test
    fun unknownEvent_returnsNull() {
        assertNull(SseEventType.fromWire("delivery.started.v2"))
        assertNull(SseEventType.fromWire("unknown.event"))
        assertNull(SseEventType.fromWire(""))
    }
}
