package com.mote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.mote.app.ai.HybridAiRouter
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.data.BotRepository
import com.mote.app.data.BotStore
import com.mote.app.data.ChatHistoryStore
import com.mote.app.data.FirestoreChatHistoryRepository
import com.mote.app.data.GuestBotStore
import com.mote.app.data.GuestChatHistoryStore
import com.mote.app.security.configureAppCheck
import com.mote.app.ui.BotsUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.auth.AuthScreen
import com.mote.app.ui.components.FloatingAssistantView
import com.mote.app.ui.components.IosFloatingBottomNav
import com.mote.app.ui.components.moteFloatingTabs
import com.mote.app.ui.screens.ChatHubScreen
import com.mote.app.ui.screens.ModelsStudioScreen
import com.mote.app.ui.screens.SecurityCenterScreen
import com.mote.app.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                MoteAppRoot()
            }
        }
    }
}

@Composable
private fun MoteAppRoot() {
    var currentUser by remember { mutableStateOf(Firebase.auth.currentUser) }
    var isGuestMode by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
            if (auth.currentUser != null) {
                isGuestMode = false
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    if (currentUser == null && !isGuestMode) {
        AuthScreen(
            onAuthSuccess = {
                isGuestMode = false
                currentUser = Firebase.auth.currentUser
            },
            onContinueAsGuest = { isGuestMode = true }
        )
    } else {
        val isGuest = currentUser == null
        val sessionKey = currentUser?.uid ?: "guest"
        val userEmail = currentUser?.email
        key(sessionKey) {
            MoteHubHost(
                sessionKey = sessionKey,
                isGuest = isGuest,
                userEmail = userEmail,
                onAuthSuccess = {
                    isGuestMode = false
                    currentUser = Firebase.auth.currentUser
                },
                onSignedOut = {
                    currentUser = null
                    isGuestMode = false
                }
            )
        }
    }
}

@Composable
private fun MoteHubHost(
    sessionKey: String,
    isGuest: Boolean,
    userEmail: String?,
    onAuthSuccess: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val store: BotStore = remember(context, isGuest) {
        if (isGuest) GuestBotStore() else BotRepository(context)
    }
    val chatStore: ChatHistoryStore = remember(context, isGuest) {
        if (isGuest) GuestChatHistoryStore() else FirestoreChatHistoryRepository(context)
    }
    val router = remember { HybridAiRouter() }
    val executor = remember(context) { DeviceActionExecutor(context) }
    val vm: MoteViewModel = viewModel(
        key = sessionKey,
        factory = MoteViewModel.Factory(store, chatStore, router)
    )

    val botsState by vm.botsState.collectAsStateWithLifecycle()
    val chatHistoryState by vm.chatHistoryState.collectAsStateWithLifecycle()
    val aiState by vm.aiState.collectAsStateWithLifecycle()
    val llmConfig by vm.llmConfig.collectAsStateWithLifecycle()
    val activeBots = (botsState as? BotsUiState.Success)?.bots.orEmpty()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { moteFloatingTabs.size })
    var showFloatingAssistant by remember { mutableStateOf(true) }

    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val pagerProgress by remember {
        derivedStateOf {
            pagerState.currentPage + pagerState.currentPageOffsetFraction
        }
    }

    fun navigateToTab(index: Int) {
        scope.launch {
            pagerState.animateScrollToPage(
                page = index,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = 340f
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ChatHubScreen(
                    viewModel = vm,
                    isGuest = isGuest,
                    isImeVisible = isImeVisible,
                    floatingAssistantVisible = showFloatingAssistant,
                    onToggleFloatingAssistant = { showFloatingAssistant = !showFloatingAssistant },
                    onOpenModelsTab = { navigateToTab(1) }
                )
                1 -> ModelsStudioScreen(
                    viewModel = vm,
                    isGuest = isGuest,
                    onSelectPersonaForChat = { botId ->
                        vm.selectBot(botId)
                        navigateToTab(0)
                    }
                )
                2 -> SecurityCenterScreen(
                    floatingAssistantVisible = showFloatingAssistant,
                    onToggleFloatingAssistant = { showFloatingAssistant = !showFloatingAssistant }
                )
                else -> SettingsScreen(
                    isGuest = isGuest,
                    userEmail = userEmail,
                    llmConfig = llmConfig,
                    onClearChatHistory = { vm.clearChatHistory() },
                    onAuthSuccess = onAuthSuccess,
                    onSignedOut = onSignedOut
                )
            }
        }

        // Smoothly hide floating bottom nav when keyboard is open in Chat
        AnimatedVisibility(
            visible = !isImeVisible,
            enter = fadeIn(animationSpec = spring(stiffness = 400f)) +
                slideInVertically(
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 360f),
                    initialOffsetY = { it / 2 }
                ),
            exit = fadeOut(animationSpec = spring(stiffness = 450f)) +
                slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
                    targetOffsetY = { it / 2 }
                ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            IosFloatingBottomNav(
                selectedIndex = pagerState.currentPage,
                pagerProgress = pagerProgress,
                onSelect = { navigateToTab(it) }
            )
        }

        if (showFloatingAssistant) {
            FloatingAssistantView(
                bots = activeBots,
                aiState = aiState,
                chatHistoryState = chatHistoryState,
                isCloudSynced = !isGuest,
                onSubmitPrompt = { prompt, bot ->
                    vm.generateFocusControlCard(
                        userPrompt = prompt,
                        botSystemPrompt = bot?.systemPrompt
                            ?: "Return compact Android Generative UI controls.",
                        botId = bot?.id ?: "focus",
                        botName = bot?.name ?: "Focus"
                    )
                },
                onExecuteAction = { action -> executor.execute(action) },
                onClearHistory = { vm.clearChatHistory() },
                onDismissOverlay = { showFloatingAssistant = false }
            )
        }
    }
}
