package com.mote.app

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.mote.app.control.DeviceAutomationToolsManager
import com.mote.app.data.BotRepository
import com.mote.app.data.BotStore
import com.mote.app.data.ChatHistoryStore
import com.mote.app.data.FirestoreChatHistoryRepository
import com.mote.app.data.GuestBotStore
import com.mote.app.data.GuestChatHistoryStore
import com.mote.app.data.LocalModelManager
import com.mote.app.security.configureAppCheck
import com.mote.app.service.MediaProjectionConsentBridge
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.auth.AuthScreen
import com.mote.app.ui.components.IosCanvas
import com.mote.app.ui.components.IosFloatingBottomNav
import com.mote.app.ui.components.moteFloatingTabs
import com.mote.app.ui.screens.ChatHubScreen
import com.mote.app.ui.screens.GenUiStudioScreen
import com.mote.app.ui.screens.ModelsStudioScreen
import com.mote.app.ui.screens.SecurityCenterScreen
import com.mote.app.ui.screens.SettingsScreen
import com.mote.app.ui.screens.SystemHealthInferenceScreen
import com.mote.app.ui.theme.MoteTheme
import com.mote.app.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var pendingMediaProjectionCallback: ((Int, Intent?) -> Unit)? = null

    private val mediaProjectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingMediaProjectionCallback
        pendingMediaProjectionCallback = null
        cb?.invoke(result.resultCode, result.data)
    }

    private val mediaProjectionRequester: (Intent, (Int, Intent?) -> Unit) -> Unit = { intent, callback ->
        pendingMediaProjectionCallback = callback
        mediaProjectionConsentLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        ThemePreferenceStore.init(applicationContext)
        enableEdgeToEdge()

        setContent {
            val isDarkTheme by ThemePreferenceStore.isDarkTheme.collectAsStateWithLifecycle()

            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                )
            }

            MoteTheme(darkTheme = isDarkTheme) {
                MoteAppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DeviceAutomationToolsManager.attachForegroundActivity(this)
        MediaProjectionConsentBridge.attachConsentLauncher(mediaProjectionRequester)
    }

    override fun onPause() {
        DeviceAutomationToolsManager.detachForegroundActivity(this)
        MediaProjectionConsentBridge.detachConsentLauncher(mediaProjectionRequester)
        super.onPause()
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
    val localModelManager = remember(context) { LocalModelManager(context) }
    val router = remember { HybridAiRouter() }
    val vm: MoteViewModel = viewModel(
        key = sessionKey,
        factory = MoteViewModel.Factory(store, chatStore, router, localModelManager)
    )

    val llmConfig by vm.llmConfig.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { moteFloatingTabs.size })
    var showHealthDashboardScreen by remember { mutableStateOf(false) }

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

    DisposableEffect(Unit) {
        val navHandler: (String) -> Unit = { tabKey ->
            val targetIdx = when (tabKey.lowercase()) {
                "chat" -> 0
                "genui", "studio" -> 1
                "models", "model", "local" -> 2
                "tools", "permissions", "security" -> 3
                "settings", "config" -> 4
                else -> -1
            }
            if (targetIdx >= 0) {
                navigateToTab(targetIdx)
            }
        }
        DeviceAutomationToolsManager.onNavigateInAppTab = navHandler
        onDispose {
            if (DeviceAutomationToolsManager.onNavigateInAppTab === navHandler) {
                DeviceAutomationToolsManager.onNavigateInAppTab = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
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
                    onOpenModelsTab = { navigateToTab(2) },
                    onOpenToolsTab = { navigateToTab(3) }
                )
                1 -> GenUiStudioScreen(viewModel = vm)
                2 -> ModelsStudioScreen(
                    viewModel = vm,
                    isGuest = isGuest,
                    onSelectPersonaForChat = { botId ->
                        vm.selectBot(botId)
                        navigateToTab(0)
                    },
                    onOpenChatTab = { navigateToTab(0) },
                    onOpenHealthDashboard = { showHealthDashboardScreen = true }
                )
                3 -> SecurityCenterScreen(
                    viewModel = vm
                )
                else -> SettingsScreen(
                    viewModel = vm,
                    isGuest = isGuest,
                    userEmail = userEmail,
                    llmConfig = llmConfig,
                    onClearChatHistory = { vm.clearChatHistory() },
                    onAuthSuccess = onAuthSuccess,
                    onSignedOut = onSignedOut,
                    onOpenHealthDashboard = { showHealthDashboardScreen = true }
                )
            }
        }

        AnimatedVisibility(
            visible = !isImeVisible && !showHealthDashboardScreen,
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

        if (showHealthDashboardScreen) {
            SystemHealthInferenceScreen(
                viewModel = vm,
                onBack = { showHealthDashboardScreen = false }
            )
        }
    }
}
