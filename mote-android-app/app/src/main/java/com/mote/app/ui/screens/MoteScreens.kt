package com.mote.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.data.BotRepository
import com.mote.app.service.BotOverlayService
import com.mote.app.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun HomeHubScreen(repo: BotRepository, router: HybridAiRouter) {
    val context = LocalContext.current
    val bots by repo.bots.collectAsState()
    val scope = rememberCoroutineScope()
    var lastCard by remember { mutableStateOf<HybridGenUiResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val executor = remember { DeviceActionExecutor(context) }

    Column(modifier = Modifier.fillMaxSize().background(IosCanvas)) {
        IosTopNavBar(title = "Mote") {
            Text("Nano 4 Ready", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalNanoEmerald)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                MoteCard(background = MoteNavy) {
                    Text("QUICK GENERATIVE UI", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    lastCard = router.generateControlCard(
                                        userPrompt = "Focus mode: dim screen to 30%, enable DND, start 25 minute timer",
                                        botSystemPrompt = "Return compact Android Focus controls.",
                                        redactedScreenContext = null,
                                        isForeground = true
                                    )
                                    busy = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LocalNanoEmerald),
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(if (busy) "Running..." else "Focus Mode", color = Color.White, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                if (Settings.canDrawOverlays(context)) {
                                    context.startForegroundService(Intent(context, BotOverlayService::class.java))
                                } else {
                                    context.startActivity(
                                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    )
                                }
                            },
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text("Spawn Bot", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            lastCard?.let { card ->
                item {
                    GenUiCardRenderer(result = card) { action -> executor.execute(action) }
                }
            }

            items(bots, key = { it.id }) { bot ->
                MoteCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(bot.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MoteNavy)
                            Text("${bot.role} · ${bot.inferenceMode}", fontSize = 11.5.sp, color = Color(0xFF64748B))
                        }
                        IosSpringSwitch(checked = bot.active, onCheckedChange = { repo.toggleBot(bot.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun BotStudioScreen(repo: BotRepository) {
    val bots by repo.bots.collectAsState()
    Column(modifier = Modifier.fillMaxSize().background(IosCanvas)) {
        IosTopNavBar(title = "Bot Studio")
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            items(bots, key = { it.id }) { bot ->
                MoteCard {
                    Text(bot.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MoteNavy)
                    Spacer(Modifier.height(4.dp))
                    Text(bot.systemPrompt, fontSize = 12.sp, color = Color(0xFF475569))
                    Spacer(Modifier.height(8.dp))
                    Text("Routing: ${bot.inferenceMode}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
                }
            }
        }
    }
}

@Composable
fun SecurityCenterScreen() {
    val context = LocalContext.current
    var piiEnabled by remember { mutableStateOf(true) }
    var blockBanking by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().background(IosCanvas)) {
        IosTopNavBar(title = "Security") {
            Text("App Check OK", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalNanoEmerald)
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MoteCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Local PII Redaction", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                        Text("Masks emails, phones, OTPs, and cards on-device.", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    IosSpringSwitch(checked = piiEnabled, onCheckedChange = { piiEnabled = it })
                }
            }
            MoteCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block Banking & FLAG_SECURE", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                        Text("Skips protected windows and password inputs.", fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                    IosSpringSwitch(checked = blockBanking, onCheckedChange = { blockBanking = it })
                }
            }
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Accessibility Settings", color = Color.White)
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    var haptics by remember { mutableStateOf(true) }
    Column(modifier = Modifier.fillMaxSize().background(IosCanvas)) {
        IosTopNavBar(title = "Settings")
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MoteCard {
                Text("FIREBASE REMOTE CONFIG", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
                Spacer(Modifier.height(6.dp))
                Text("Cloud Fallback Model: gemini-2.5-flash", fontSize = 13.sp, color = MoteNavy)
                Text("Local Token Cap: 4,000 tokens", fontSize = 13.sp, color = Color(0xFF475569))
            }
            MoteCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Haptic Bot Feedback", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                    IosSpringSwitch(checked = haptics, onCheckedChange = { haptics = it })
                }
            }
        }
    }
}
