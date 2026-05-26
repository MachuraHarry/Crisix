package com.messenger.crisix.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.messenger.crisix.transport.DummyTransport
import com.messenger.crisix.transport.TransportManager
import com.messenger.crisix.ui.screens.ChatDetailScreen
import com.messenger.crisix.ui.screens.ChatListScreen
import com.messenger.crisix.ui.screens.ChatPreview
import com.messenger.crisix.ui.screens.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrisixApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val transportManager = remember { TransportManager() }

    // DummyTransport initialisieren
    LaunchedEffect(Unit) {
        val dummyTransport = DummyTransport()
        transportManager.registerTransport(dummyTransport)
        transportManager.selectBestTransport()
    }

    val activeTransport by transportManager.activeTransport.collectAsState()
    val capabilities = transportManager.getCurrentCapabilities()

    // Dummy-Daten für die Chat-Liste
    val dummyChats = remember {
        listOf(
            ChatPreview(
                id = "dummy-1",
                name = "Max Mustermann",
                lastMessage = "Hey, wie geht's?",
                timestamp = "12:30",
                transportType = activeTransport?.type
            ),
            ChatPreview(
                id = "dummy-2",
                name = "Erika Musterfrau",
                lastMessage = "Bin gleich da!",
                timestamp = "11:15",
                transportType = activeTransport?.type
            )
        )
    }

    // Dummy-Nachrichten pro Chat
    val dummyMessages = remember {
        mutableMapOf(
            "dummy-1" to listOf(
                Message("m1", "Hey, wie geht's?", false, "12:30"),
                Message("m2", "Mir geht's gut, und dir?", true, "12:31"),
                Message("m3", "Auch gut! Hast du den Plan gesehen?", false, "12:32"),
                Message("m4", "Ja, sieht super aus!", true, "12:33")
            ),
            "dummy-2" to listOf(
                Message("m5", "Bin gleich da!", false, "11:15"),
                Message("m6", "Super, ich warte!", true, "11:16")
            )
        )
    }

    var currentMessages by remember { mutableStateOf(dummyMessages["dummy-1"] ?: emptyList()) }

    NavHost(
        navController = navController,
        startDestination = NavRoutes.CHAT_LIST,
        modifier = modifier
    ) {
        // Chat-Liste
        composable(NavRoutes.CHAT_LIST) {
            ChatListScreen(
                chats = dummyChats,
                onChatClick = { chatId, chatName ->
                    currentMessages = dummyMessages[chatId] ?: emptyList()
                    navController.navigate(NavRoutes.chatDetail(chatId, chatName))
                }
            )
        }

        // Chat-Detail
        composable(
            route = NavRoutes.CHAT_DETAIL,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("chatName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
            val chatName = backStackEntry.arguments?.getString("chatName") ?: ""

            ChatDetailScreen(
                chatId = chatId,
                chatName = chatName,
                transportType = activeTransport?.type,
                capabilities = capabilities,
                messages = currentMessages,
                onBackClick = { navController.popBackStack() },
                onSendMessage = { text ->
                    val timeStamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    val newMessage = Message(
                        id = "m${System.currentTimeMillis()}",
                        text = text,
                        isFromMe = true,
                        timestamp = timeStamp
                    )
                    currentMessages = currentMessages + newMessage
                }
            )
        }
    }
}
