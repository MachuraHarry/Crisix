package com.messenger.crisix.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.messenger.crisix",
        maxIterations = 10,
    ) {
        pressHome()
        startActivityAndWait {
            it.setPackage(packageName)
        }

        device.waitForIdle()

        val scrollable = device.findObject(By.scrollable(true))
        if (scrollable != null) {
            scrollable.setGestureMargin(device.displayWidth / 3)
            repeat(8) {
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
            }
        }

        device.waitForIdle()
    }
}
