package com.mote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.ui.AiCardUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors

private data class GenUiShowcasePrompt(
    val title: String,
    val badge: String,
    val prompt: String
)

private val genUiShowcasePrompts = listOf(
    GenUiShowcasePrompt(
        title = "Weekly Calendar",
        badge = "calendar",
        prompt = "Build a weekly study and deep-work calendar schedule for Monday to Thursday with time windows and rooms."
    ),
    GenUiShowcasePrompt(
        title = "Benchmark Chart",
        badge = "bar_chart",
        prompt = "Show a bar chart and metric grid comparing mobile LLM latency and throughput across 4 quantized model sizes."
    ),
    GenUiShowcasePrompt(
        title = "Focus Controls",
        badge = "controls",
        prompt = "Create Focus Mode Android controls: dim brightness slider to 30%, DND toggle, and 25 minute timer action."
    ),
    GenUiShowcasePrompt(
        title = "Kotlin Quiz",
        badge = "quiz_card",
        prompt = "Generate an interactive Kotlin Coroutines quiz card with 4 options and a concise explanation."
    ),
    GenUiShowcasePrompt(
        title = "Model Comparison",
        badge = "comparison",
        prompt = "Compare Cloud Gemini 3.8 Flash vs Local 1B GGUF across latency, privacy, multimodal vision, and offline use."
    ),
    GenUiShowcasePrompt(
        title = "Sprint Checklist",
        badge = "checklist",
        prompt = "Create an interactive release checklist and 4-step launch timeline for shipping an Android app."
    )
)

@Composable
fun GenUiStudioScreen(viewModel: MoteViewModel) {
    val context = LocalContext.current
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val llmConfig by viewModel.llmConfig.collectAsStateWithLifecycle()
    val executor = remember(context) { DeviceActionExecutor(context) }

    var customPrompt by remember { mutableStateOf("") }
    var inspectJson by remember { mutableStateOf(false) }
    val registryDescriptors = remember { GenUiComponentRegistry.registeredDescriptors() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "GenUI Studio",
            subtitle = "${registryDescriptors.size} registered native Compose widgets"
        ) {
            LobeModelAvatar(modelId = llmConfig.modelName, size = 34.dp)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            item(key = "genui_hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(MoteBrandNavy, Color(0xFF172554))))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DashboardCustomize,
                            contentDescription = null,
                            tint = LocalNanoEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "GENERATIVE UI COMPONENT REGISTRY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF93C5FD)
                        )
                    }
                    Text(
                        text = "Prompt Gemini to synthesize live interactive widgets — charts, calendars, checklists, quizzes, and device controls.",
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        lineHeight = 18.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customPrompt,
                            onValueChange = { customPrompt = it },
                            placeholder = {
                                Text("Describe a widget or dashboard...", fontSize = 13.sp, color = Color(0xFF94A3B8))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = LocalNanoEmerald,
                                unfocusedBorderColor = Color(0x33FFFFFF),
                                focusedContainerColor = Color(0x1FFFFFFF),
                                unfocusedContainerColor = Color(0x14FFFFFF)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("genui_studio_prompt_input")
                        )
                        FilledIconButton(
                            onClick = {
                                val text = customPrompt.trim()
                                if (text.isNotEmpty()) {
                                    viewModel.generateFocusControlCard(
                                        userPrompt = text,
                                        botSystemPrompt = "Compose rich native Android Generative UI widgets using the registered component catalog.",
                                        botId = "genui_studio",
                                        botName = "GenUI"
                                    )
                                }
                            },
                            enabled = customPrompt.isNotBlank() && aiState !is AiCardUiState.Loading,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = LocalNanoEmerald,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("genui_studio_generate_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Generate UI", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Registered Component Catalog Strip
            item(key = "registry_catalog") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "REGISTERED WIDGET TYPES",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateMuted,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        registryDescriptors.forEach { desc ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MoteSurface)
                                    .border(1.dp, MoteBorder, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${desc.displayName} · ${desc.category}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MoteNavy
                                )
                            }
                        }
                    }
                }
            }

            // Showcase Presets
            item(key = "showcase_presets") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "TRY A LIVE GENERATIVE UI",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateMuted,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    genUiShowcasePrompts.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { preset ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MoteSurface)
                                        .border(1.dp, MoteBorder, RoundedCornerShape(14.dp))
                                        .clickable {
                                            customPrompt = preset.prompt
                                            viewModel.generateFocusControlCard(
                                                userPrompt = preset.prompt,
                                                botSystemPrompt = "Compose rich native Android Generative UI widgets using the registered component catalog.",
                                                botId = "genui_studio",
                                                botName = "GenUI"
                                            )
                                        }
                                        .padding(12.dp)
                                        .testTag("genui_preset_${preset.badge}")
                                ) {
                                    Text(preset.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = preset.badge,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CloudFlashCobalt
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Rendered Output + UI / JSON Inspector Toggle
            when (val state = aiState) {
                is AiCardUiState.Loading -> {
                    item(key = "studio_loading") {
                        MoteCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CloudFlashCobalt, strokeWidth = 2.dp)
                                Text(
                                    text = "Synthesizing native Jetpack Compose components...",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                            }
                        }
                    }
                }
                is AiCardUiState.Success -> {
                    item(key = "studio_mode_toggle") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("GENERATED SURFACE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                            val activeTabBg = LocalMoteColors.current.primaryActionBg
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MoteSurface)
                                    .border(1.dp, MoteBorder, RoundedCornerShape(999.dp))
                                    .padding(3.dp)
                            ) {
                                Text(
                                    text = "UI View",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!inspectJson) Color.White else SlateMuted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (!inspectJson) activeTabBg else Color.Transparent)
                                        .clickable { inspectJson = false }
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (inspectJson) activeTabBg else Color.Transparent)
                                        .clickable { inspectJson = true }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Code,
                                        contentDescription = null,
                                        tint = if (inspectJson) Color.White else SlateMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = "JSON",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (inspectJson) Color.White else SlateMuted
                                    )
                                }
                            }
                        }
                    }
                    item(key = "studio_output") {
                        if (inspectJson) {
                            MoteCard(background = MoteBrandNavy) {
                                Text(
                                    text = state.result.rawJson,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF93C5FD),
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            GenUiCardRenderer(result = state.result, onExecuteAction = { executor.execute(it) })
                        }
                    }
                }
                is AiCardUiState.Error -> {
                    item(key = "studio_error") {
                        MoteCard(background = Color(0xFFFEF2F2)) {
                            Text("Generation Error", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Color(0xFFB91C1C))
                            Spacer(Modifier.height(4.dp))
                            Text(state.message, fontSize = 12.sp, color = Color(0xFF7F1D1D))
                        }
                    }
                }
                is AiCardUiState.Idle -> Unit
            }
        }
    }
}
