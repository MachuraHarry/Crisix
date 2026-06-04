package com.messenger.crisix.util

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DateGrouperTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun startOfDayMillis(daysAgo: Long): Long {
        val date = LocalDate.now(zone).minusDays(daysAgo)
        return date.atStartOfDay(zone).toInstant().toEpochMilli() + 1000L // 1 sec after start
    }

    @Test
    fun `timestamp zero returns OLDER`() {
        assertEquals(DateGroup.OLDER, getDateGroup(0L))
    }

    @Test
    fun `current time returns TODAY`() {
        assertEquals(DateGroup.TODAY, getDateGroup(System.currentTimeMillis()))
    }

    @Test
    fun `timestamp from today returns TODAY`() {
        val todayMillis = startOfDayMillis(0)
        assertEquals(DateGroup.TODAY, getDateGroup(todayMillis))
    }

    @Test
    fun `timestamp from yesterday returns YESTERDAY`() {
        val yesterdayMillis = startOfDayMillis(1)
        assertEquals(DateGroup.YESTERDAY, getDateGroup(yesterdayMillis))
    }

    @Test
    fun `timestamp from 2 days ago returns THIS_WEEK`() {
        val twoDaysAgo = startOfDayMillis(2)
        assertEquals(DateGroup.THIS_WEEK, getDateGroup(twoDaysAgo))
    }

    @Test
    fun `timestamp from 7 days ago returns THIS_WEEK`() {
        val sevenDaysAgo = startOfDayMillis(7)
        assertEquals(DateGroup.THIS_WEEK, getDateGroup(sevenDaysAgo))
    }

    @Test
    fun `timestamp from 8 days ago returns OLDER`() {
        val eightDaysAgo = startOfDayMillis(8)
        assertEquals(DateGroup.OLDER, getDateGroup(eightDaysAgo))
    }

    @Test
    fun `timestamp from 30 days ago returns OLDER`() {
        val thirtyDaysAgo = startOfDayMillis(30)
        assertEquals(DateGroup.OLDER, getDateGroup(thirtyDaysAgo))
    }

    @Test
    fun `very old timestamp returns OLDER`() {
        // January 1, 2000
        assertEquals(DateGroup.OLDER, getDateGroup(946684800000L))
    }

    @Test
    fun `DateGroup enum has four values`() {
        val values = DateGroup.values()
        assertEquals(4, values.size)
        assertTrue(values.contains(DateGroup.TODAY))
        assertTrue(values.contains(DateGroup.YESTERDAY))
        assertTrue(values.contains(DateGroup.THIS_WEEK))
        assertTrue(values.contains(DateGroup.OLDER))
    }
}
