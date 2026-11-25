package com.mote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mote.app.ai.HybridAiRouter
import com.mote.app.data.BotRepository
import com.mote.app.ui.components.IosFloatingBottomNav
import com.mote.app.ui.screens.BotStudioScreen
import com.mote.app.ui.screens.HomeHubScreen
import com.mote.app.ui.screens.SecurityCenterScreen
import com.mote.app.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {

    private val repo = BotRepository()
    private val router = HybridAiRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var selectedTab by remember { mutableIntStateOf(0) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "moteTabTransition"
                    ) { tab ->
                        when (tab) {
                            0 -> HomeHubScreen(repo = repo, router = router)
                            1 -> BotStudioScreen(repo = repo)
                            2 -> SecurityCenterScreen()
                            else -> SettingsScreen()
                        }
                    }

                    IosFloatingBottomNav(
                        selectedIndex = selectedTab,
                        onSelect = { selectedTab = it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }
}
