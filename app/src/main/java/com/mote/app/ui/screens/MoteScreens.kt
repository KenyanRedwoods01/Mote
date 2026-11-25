package com.mote.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.mote.app.ai.LlmConfig
import com.mote.app.service.BotOverlayService
import com.mote.app.ui.auth.GoogleSignInButton
import com.mote.app.ui.auth.signOut
import com.mote.app.ui.components.*
import java.util.Locale

@Composable
fun SecurityCenterScreen(
    floatingAssistantVisible: Boolean = true,
    onToggleFloatingAssistant: () -> Unit = {}
) {
    val context = LocalContext.current
    var piiEnabled by remember { mutableStateOf(true) }
    var blockBanking by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Privacy & Overlay",
            subtitle = "On-device guards & system floating surface"
        ) {
            Text(
                text = "App Check OK",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalNanoEmerald
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            item {
                MoteCard(background = MoteNavy) {
                    Text(
                        text = "FLOATING ASSISTANT SURFACE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Keep Mote's draggable orb available inside the app or launch a system overlay across other Android apps.",
                        fontSize = 12.5.sp,
                        color = Color(0xFFCBD5E1),
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onToggleFloatingAssistant,
                            colors = ButtonDefaults.buttonColors(containerColor = LocalNanoEmerald),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("privacy_toggle_orb_button")
                        ) {
                            Text(
                                text = if (floatingAssistantVisible) "Hide In-App Orb" else "Show In-App Orb",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (Settings.canDrawOverlays(context)) {
                                context.startForegroundService(Intent(context, BotOverlayService::class.java))
                            } else {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("spawn_bot_button")
                    ) {
                        Text(
                            text = "Spawn System Overlay Over Other Apps",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            item {
                MoteCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Local PII Redaction", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Masks emails, phones, OTPs, and cards on-device.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = piiEnabled, onCheckedChange = { piiEnabled = it })
                    }
                }
            }
            item {
                MoteCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Block Banking & FLAG_SECURE", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Skips protected windows and password inputs.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = blockBanking, onCheckedChange = { blockBanking = it })
                    }
                }
            }
            item {
                Button(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("accessibility_settings_button")
                ) {
                    Text("Open Accessibility Settings", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    isGuest: Boolean,
    userEmail: String?,
    llmConfig: LlmConfig,
    onClearChatHistory: () -> Unit,
    onAuthSuccess: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var haptics by remember { mutableStateOf(true) }
    var authError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Settings",
            subtitle = "Engine configuration & account sync"
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            item {
                MoteCard {
                    Text("FIREBASE AI LOGIC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CloudFlashCobalt)
                    Spacer(Modifier.height(6.dp))
                    Text("Active Model: ${llmConfig.modelName}", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "Temp %.2f · Max %d tokens · %d-msg memory",
                            llmConfig.temperature,
                            llmConfig.maxOutputTokens,
                            llmConfig.contextWindowTurns
                        ),
                        fontSize = 12.sp,
                        color = SlateMuted
                    )
                }
            }
            item {
                MoteCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Haptic Navigation & Feedback", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MoteNavy)
                            Text("Subtle tactile ticks on tab transitions and orb actions.", fontSize = 12.sp, color = SlateMuted)
                        }
                        IosSpringSwitch(checked = haptics, onCheckedChange = { haptics = it })
                    }
                }
            }
            item {
                MoteCard {
                    Text("CONVERSATION DATA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (isGuest) {
                            "Stored locally in memory for this guest session."
                        } else {
                            "Synced securely to your private Firestore chat history."
                        },
                        fontSize = 12.sp,
                        color = SlateMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onClearChatHistory,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("settings_clear_history_button")
                    ) {
                        Text("Clear Chat History", color = Color(0xFFB91C1C), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                MoteCard {
                    Text("ACCOUNT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                    Spacer(Modifier.height(6.dp))
                    if (isGuest) {
                        Text(
                            text = "Guest Mode (Local only)",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoteNavy
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Sign in with Google to sync your bot personas and chat history to Firestore.",
                            fontSize = 12.sp,
                            color = SlateMuted
                        )
                        Spacer(Modifier.height(12.dp))
                        GoogleSignInButton(
                            onAuthSuccess = {
                                authError = null
                                onAuthSuccess()
                            },
                            onAuthError = { msg -> authError = msg }
                        )
                        authError?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(msg, fontSize = 12.sp, color = Color(0xFFB91C1C))
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = onSignedOut,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("exit_guest_mode_button")
                        ) {
                            Text("Back to Sign-In Screen", color = SlateMuted, fontSize = 12.5.sp)
                        }
                    } else {
                        Text(
                            text = userEmail ?: "Signed in with Google",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MoteNavy
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                signOut(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onSignOutComplete = onSignedOut
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Text("Sign Out", color = MoteNavy, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
