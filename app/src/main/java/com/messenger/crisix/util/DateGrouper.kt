package com.messenger.crisix.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DateGroup { TODAY, YESTERDAY, THIS_WEEK, OLDER }

private val SYSTEM_ZONE: ZoneId = ZoneId.systemDefault()

fun getDateGroup(timestampMillis: Long): DateGroup {
    if (timestampMillis == 0L) return DateGroup.OLDER
    val msgDay = Instant.ofEpochMilli(timestampMillis).atZone(SYSTEM_ZONE).toLocalDate().toEpochDay()
    val nowDay = LocalDate.now(SYSTEM_ZONE).toEpochDay()
    val diff = nowDay - msgDay
    return when {
        diff == 0L -> DateGroup.TODAY
        diff == 1L -> DateGroup.YESTERDAY
        diff in 2L..7L -> DateGroup.THIS_WEEK
        else -> DateGroup.OLDER
    }
}
