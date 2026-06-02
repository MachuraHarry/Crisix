package com.messenger.crisix.util

import java.util.Calendar

enum class DateGroup { TODAY, YESTERDAY, THIS_WEEK, OLDER }

fun getDateGroup(timestampMillis: Long): DateGroup {
    if (timestampMillis == 0L) return DateGroup.OLDER
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestampMillis }

    if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)) {
        return DateGroup.TODAY
    }

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && yesterday.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)) {
        return DateGroup.YESTERDAY
    }

    if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR)
        && now.get(Calendar.WEEK_OF_YEAR) == msgTime.get(Calendar.WEEK_OF_YEAR)) {
        return DateGroup.THIS_WEEK
    }

    return DateGroup.OLDER
}
