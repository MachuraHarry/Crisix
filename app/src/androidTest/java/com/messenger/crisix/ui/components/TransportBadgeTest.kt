package com.messenger.crisix.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.messenger.crisix.crypto.E2eeSessionState
import com.messenger.crisix.transport.TransportType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportBadgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `badge is hidden when active transport is null`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = null,
                isFallback = false,
                sessionState = E2eeSessionState.ACTIVE,
            )
        }
        composeTestRule.onNodeWithText("WIFI_DIRECT", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `badge shows BLE transport name`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.BLUETOOTH_MESH,
                isFallback = false,
                sessionState = E2eeSessionState.ACTIVE,
            )
        }
        composeTestRule.onNodeWithText("BLUETOOTH_MESH").assertExists()
    }

    @Test
    fun `badge shows WIFI_DIRECT transport name`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.WIFI_DIRECT,
                isFallback = false,
                sessionState = E2eeSessionState.ACTIVE,
            )
        }
        composeTestRule.onNodeWithText("WIFI_DIRECT").assertExists()
    }

    @Test
    fun `badge shows RELAY transport name`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.RELAY,
                isFallback = false,
                sessionState = E2eeSessionState.ACTIVE,
            )
        }
        composeTestRule.onNodeWithText("RELAY").assertExists()
    }

    @Test
    fun `badge shows Fallback prefix when isFallback true`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.BLUETOOTH_MESH,
                isFallback = true,
                sessionState = E2eeSessionState.STALE,
            )
        }
        composeTestRule.onNodeWithText("Fallback: BLUETOOTH_MESH").assertExists()
    }

    @Test
    fun `badge shows Handshake label for HANDSHAKING state`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.WIFI_DIRECT,
                isFallback = false,
                sessionState = E2eeSessionState.HANDSHAKING,
            )
        }
        composeTestRule.onNodeWithText("🔄 Handshake").assertExists()
    }

    @Test
    fun `badge shows STALE label for STALE state`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.RELAY,
                isFallback = false,
                sessionState = E2eeSessionState.STALE,
            )
        }
        composeTestRule.onNodeWithText("🔓 STALE").assertExists()
    }

    @Test
    fun `badge shows Active lock for ACTIVE state`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.WIFI_DIRECT,
                isFallback = false,
                sessionState = E2eeSessionState.ACTIVE,
            )
        }
        composeTestRule.onNodeWithText("🔒 Aktiv").assertExists()
    }

    @Test
    fun `badge shows Compromised warning for COMPROMISED state`() {
        composeTestRule.setContent {
            TransportBadge(
                activeTransport = TransportType.WIFI_DIRECT,
                isFallback = false,
                sessionState = E2eeSessionState.COMPROMISED,
            )
        }
        composeTestRule.onNodeWithText("⚠️ COMPROMISED").assertExists()
    }
}
