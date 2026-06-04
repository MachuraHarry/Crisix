package com.messenger.crisix.crypto

import org.junit.Assert.*
import org.junit.Test

class SessionCleanupManagerDataTest {

    // =========================================================================
    // CleanupResult Tests
    // =========================================================================

    @Test
    fun `CleanupResult getLogMessage contains counts`() {
        val result = SessionCleanupManager.CleanupResult(
            deletedCount = 3,
            warningCount = 2,
            remainingCount = 10,
            deletedPeerIds = listOf("peer-a", "peer-b", "peer-c")
        )

        val msg = result.getLogMessage()
        assertTrue(msg.contains("3"))
        assertTrue(msg.contains("2"))
        assertTrue(msg.contains("10"))
    }

    @Test
    fun `CleanupResult default deletedPeerIds is empty`() {
        val result = SessionCleanupManager.CleanupResult(
            deletedCount = 0,
            warningCount = 0,
            remainingCount = 5
        )
        assertTrue(result.deletedPeerIds.isEmpty())
    }

    @Test
    fun `CleanupResult stores deleted peer ids`() {
        val peers = listOf("peer-1", "peer-2")
        val result = SessionCleanupManager.CleanupResult(
            deletedCount = 2,
            warningCount = 0,
            remainingCount = 3,
            deletedPeerIds = peers
        )
        assertEquals(2, result.deletedPeerIds.size)
        assertEquals("peer-1", result.deletedPeerIds[0])
        assertEquals("peer-2", result.deletedPeerIds[1])
    }

    // =========================================================================
    // SessionStatus Tests
    // =========================================================================

    @Test
    fun `SessionStatus getStatus returns EXPIRED for expired sessions`() {
        val status = SessionCleanupManager.SessionStatus(
            peerId = "peer-old",
            lastAccessTime = 0L,
            ageInDays = 91,
            daysUntilExpiry = 0,
            isExpired = true,
            isWarning = false
        )
        assertTrue(status.getStatus().contains("EXPIRED"))
    }

    @Test
    fun `SessionStatus getStatus returns WARNING for warning sessions`() {
        val status = SessionCleanupManager.SessionStatus(
            peerId = "peer-warn",
            lastAccessTime = System.currentTimeMillis(),
            ageInDays = 65,
            daysUntilExpiry = 25,
            isExpired = false,
            isWarning = true
        )
        val statusText = status.getStatus()
        assertTrue(statusText.contains("WARNING"))
        assertTrue(statusText.contains("25"))
    }

    @Test
    fun `SessionStatus getStatus returns ACTIVE for active sessions`() {
        val status = SessionCleanupManager.SessionStatus(
            peerId = "peer-active",
            lastAccessTime = System.currentTimeMillis(),
            ageInDays = 5,
            daysUntilExpiry = 85,
            isExpired = false,
            isWarning = false
        )
        val statusText = status.getStatus()
        assertTrue(statusText.contains("ACTIVE"))
        assertTrue(statusText.contains("85"))
    }

    // =========================================================================
    // CleanupStatistics Tests
    // =========================================================================

    @Test
    fun `CleanupStatistics getLogMessage contains all counts`() {
        val stats = SessionCleanupManager.CleanupStatistics(
            totalSessions = 20,
            expiredSessions = 3,
            warningSessions = 5,
            activeSessions = 12,
            oldestSessionDays = 88
        )

        val msg = stats.getLogMessage()
        assertTrue(msg.contains("20"))
        assertTrue(msg.contains("12"))
        assertTrue(msg.contains("5"))
        assertTrue(msg.contains("3"))
        assertTrue(msg.contains("88"))
    }

    @Test
    fun `CleanupStatistics with zero values`() {
        val stats = SessionCleanupManager.CleanupStatistics(
            totalSessions = 0,
            expiredSessions = 0,
            warningSessions = 0,
            activeSessions = 0,
            oldestSessionDays = 0
        )
        val msg = stats.getLogMessage()
        assertNotNull(msg)
        assertTrue(msg.contains("0"))
    }

    // =========================================================================
    // RetryStatus Tests (from HandshakeRetryManager)
    // =========================================================================

    @Test
    fun `RetryStatus isRetrying returns true when within max attempts`() {
        val status = HandshakeRetryManager.RetryStatus(
            peerId = "peer-1",
            attemptNumber = 3,
            maxAttempts = 5,
            lastErrorTime = System.currentTimeMillis(),
            nextRetryTime = System.currentTimeMillis() + 5000
        )
        assertTrue(status.isRetrying())
    }

    @Test
    fun `RetryStatus isRetrying returns false when attempt is 0`() {
        val status = HandshakeRetryManager.RetryStatus(
            peerId = "peer-1",
            attemptNumber = 0,
            maxAttempts = 5,
            lastErrorTime = 0,
            nextRetryTime = 0
        )
        assertFalse(status.isRetrying())
    }

    @Test
    fun `RetryStatus isRetrying returns false when exceeding max attempts`() {
        val status = HandshakeRetryManager.RetryStatus(
            peerId = "peer-1",
            attemptNumber = 6,
            maxAttempts = 5,
            lastErrorTime = System.currentTimeMillis(),
            nextRetryTime = 0
        )
        assertFalse(status.isRetrying())
    }

    @Test
    fun `RetryStatus timeUntilNextRetry returns 0 when in the past`() {
        val status = HandshakeRetryManager.RetryStatus(
            peerId = "peer-1",
            attemptNumber = 2,
            maxAttempts = 5,
            lastErrorTime = System.currentTimeMillis(),
            nextRetryTime = System.currentTimeMillis() - 10_000
        )
        assertEquals(0L, status.timeUntilNextRetry())
    }

    @Test
    fun `RetryStatus timeUntilNextRetry returns positive for future time`() {
        val futureTime = System.currentTimeMillis() + 60_000
        val status = HandshakeRetryManager.RetryStatus(
            peerId = "peer-1",
            attemptNumber = 1,
            maxAttempts = 5,
            lastErrorTime = System.currentTimeMillis(),
            nextRetryTime = futureTime
        )
        assertTrue(status.timeUntilNextRetry() > 0)
    }
}
