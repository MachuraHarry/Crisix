package com.messenger.crisix.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows empty state when no chats`() {
        composeTestRule.setContent {
            ChatListScreen(
                chats = emptyList(),
                onChatClick = { _, _ -> },
                onSettingsClick = {},
            )
        }

        composeTestRule.onNodeWithText("Keine Chats").assertExists()
    }

    @Test
    fun `shows start new chat hint in empty state`() {
        composeTestRule.setContent {
            ChatListScreen(
                chats = emptyList(),
                onChatClick = { _, _ -> },
                onSettingsClick = {},
            )
        }

        composeTestRule.onNodeWithText("Starte eine neue Unterhaltung").assertExists()
    }

    @Test
    fun `shows chat names when chats exist`() {
        val chats = listOf(
            ChatPreview(id = "aaa", name = "Alice", lastMessage = "Hallo",
                timestamp = "12:00", timestampMillis = 5000),
            ChatPreview(id = "bbb", name = "Bob", lastMessage = "Hey",
                timestamp = "11:00", timestampMillis = 4000),
        )

        composeTestRule.setContent {
            ChatListScreen(
                chats = chats,
                onChatClick = { _, _ -> },
                onSettingsClick = {},
            )
        }

        composeTestRule.onNodeWithText("Alice").assertExists()
        composeTestRule.onNodeWithText("Bob").assertExists()
    }
}
