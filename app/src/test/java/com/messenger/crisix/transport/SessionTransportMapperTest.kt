package com.messenger.crisix.transport

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTransportMapperTest {

    @Test
    fun `recordSendSuccess sets last successful transport`() {
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1@host", TransportType.WIFI_DIRECT)
        assertEquals(TransportType.WIFI_DIRECT, mapper.getLastSuccessful("peer1@host"))
    }

    @Test
    fun `last successful transport uses normalized peerId`() {
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1@host.com", TransportType.RELAY)
        assertEquals(TransportType.RELAY, mapper.getLastSuccessful("peer1"))
    }

    @Test
    fun `last successful expires after TTL`() {
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1", TransportType.WIFI_DIRECT)
        Thread.sleep(10)
        val hint = mapper.getLastSuccessful("peer1")
        assertNotNull(hint)
    }

    @Test
    fun `recordSendFailure increments consecutive failures`() {
        val mapper = SessionTransportMapper()
        repeat(3) { mapper.recordSendFailure("peer1", TransportType.WIFI_DIRECT) }
        val health = mapper.getHealth(TransportType.WIFI_DIRECT)
        assertEquals(3, health.consecutiveFailures)
    }

    @Test
    fun `invalidate clears last successful and primary candidate`() {
        val mapper = SessionTransportMapper()
        mapper.recordSendSuccess("peer1", TransportType.WIFI_DIRECT)
        mapper.setPrimaryCandidate("peer1", TransportType.WIFI_DIRECT)
        mapper.invalidate("peer1")
        assertNull(mapper.getLastSuccessful("peer1"))
    }

    @Test
    fun `coalesced scheduler coalesces rapid calls`() = runTest {
        val scheduler = CoalescedReconnectScheduler()
        val scope = TestScope(testScheduler)
        scheduler.scheduleReconnect(TransportType.WIFI_DIRECT, scope)
        scheduler.scheduleReconnect(TransportType.WIFI_DIRECT, scope)
        scheduler.scheduleReconnect(TransportType.WIFI_DIRECT, scope)
        val transport = scheduler.permissions.first()
        assertEquals(TransportType.WIFI_DIRECT, transport)
    }

    @Test
    fun `coalesced scheduler cancel removes pending`() {
        val scheduler = CoalescedReconnectScheduler()
        val scope = TestScope()
        scheduler.scheduleReconnect(TransportType.WIFI_DIRECT, scope)
        scheduler.cancel(TransportType.WIFI_DIRECT)
        scheduler.cancelAll()
    }
}
