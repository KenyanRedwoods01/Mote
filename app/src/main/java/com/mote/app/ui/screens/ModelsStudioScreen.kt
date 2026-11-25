package com.mote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.HybridAiRouter
import com.mote.app.model.BotPersona
import com.mote.app.model.MoteInferenceMode
import com.mote.app.ui.BotsUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import java.util.Locale
import kotlin.math.roundToInt

private val personaAccentPalette = listOf(
    0xFF059669L,
    0xFF1D4ED8L,
    0xFF0B0D1DL,
    0xFFD97706L,
    0xFF7C3AEDL
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsStudioScreen(
    viewModel: MoteViewModel,
    isGuest: Boolean,
    onSelectPersonaForChat: (String) -> Unit
) {
    val botsState by viewModel.botsState.collectAsStateWithLifecycle()
    val llmConfig by viewModel.llmConfig.collectAsStateWithLifecycle()
    val selectedBotId by viewModel.selectedBotId.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
    ) {
        IosTopNavBar(
            title = "Models & Personas",
            subtitle = if (isGuest) "Guest Session · Local Personas" else "Cloud Synced · Firebase AI Logic"
        ) {
            FilledTonalButton(
                onClick = { showCreateSheet = true },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MoteNavy,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .heightIn(min = 42.dp)
                    .testTag("new_persona_button")
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            // Section 1: Model Engine Switcher (inspired by PrivateLM)
            item(key = "models_section_header") {
                Text(
                    text = "ACTIVE GEMINI MODEL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateMuted,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            items(HybridAiRouter.supportedModels, key = { it.id }) { model ->
                val isSelected = llmConfig.modelName == model.id
                MoteCard(
                    background = if (isSelected) Color.White else Color(0xFFF8FAFC),
                    modifier = Modifier
                        .border(
                            width = if (isSelected) 1.5.dp else 0.dp,
                            color = if (isSelected) CloudFlashCobalt else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { viewModel.updateModel(model.id) }
                        .testTag("model_option_${model.id}")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = model.displayName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (isSelected) Color(0xFFEFF6FF) else Color(0xFFE2E8F0))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = model.contextLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) CloudFlashCobalt else SlateMuted
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = model.badge,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LocalNanoEmerald
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = model.description,
                                fontSize = 12.sp,
                                color = SlateMuted,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Select model",
                            tint = if (isSelected) CloudFlashCobalt else SlateMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Section 2: Configurable Inference Parameters (inspired by local-llms-on-android)
            item(key = "inference_params_card") {
                MoteCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = CloudFlashCobalt,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "INFERENCE PARAMETERS",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MoteNavy
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    ParameterSliderRow(
                        label = "Temperature",
                        valueText = String.format(Locale.US, "%.2f", llmConfig.temperature),
                        value = llmConfig.temperature,
                        range = 0f..1.5f,
                        onValueChange = { viewModel.updateTemperature(it) }
                    )

                    Spacer(Modifier.height(8.dp))

                    ParameterSliderRow(
                        label = "Max Output Tokens",
                        valueText = "${llmConfig.maxOutputTokens}",
                        value = llmConfig.maxOutputTokens.toFloat(),
                        range = 256f..4096f,
                        onValueChange = { viewModel.updateMaxOutputTokens((it / 128f).roundToInt() * 128) }
                    )

                    Spacer(Modifier.height(8.dp))

                    ParameterSliderRow(
                        label = "Context Memory",
                        valueText = "${llmConfig.contextWindowTurns} msgs",
                        value = llmConfig.contextWindowTurns.toFloat(),
                        range = 2f..20f,
                        onValueChange = { viewModel.updateContextWindowTurns(it.roundToInt()) }
                    )
                }
            }

            // Section 3: Custom Bot Personas
            item(key = "personas_section_header") {
                Text(
                    text = "BOT PERSONAS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateMuted,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            when (val state = botsState) {
                is BotsUiState.Loading -> {
                    item(key = "bots_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MoteNavy, strokeWidth = 2.5.dp)
                        }
                    }
                }
                is BotsUiState.Error -> {
                    item(key = "bots_error") {
                        MoteCard(background = Color(0xFFFEF2F2)) {
                            Text(state.message, fontSize = 12.5.sp, color = Color(0xFFB91C1C))
                        }
                    }
                }
                is BotsUiState.Success -> {
                    items(state.bots, key = { it.id }) { bot ->
                        val isCurrentChatBot = bot.id == selectedBotId
                        MoteCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(Color(bot.colorHex))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bot.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MoteNavy)
                                        Text(bot.role, fontSize = 11.5.sp, color = SlateMuted)
                                    }
                                }
                                IosSpringSwitch(
                                    checked = bot.active,
                                    onCheckedChange = { viewModel.toggleBot(bot) },
                                    modifier = Modifier.testTag("bot_toggle_${bot.id}")
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = bot.systemPrompt,
                                fontSize = 12.sp,
                                color = Color(0xFF475569),
                                lineHeight = 17.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Mode: ${bot.inferenceMode}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                                TextButton(
                                    onClick = { onSelectPersonaForChat(bot.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text(
                                        text = if (isCurrentChatBot) "Active in Chat" else "Chat with ${bot.name}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrentChatBot) LocalNanoEmerald else MoteNavy
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreatePersonaBottomSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { newBot ->
                viewModel.saveBotPersona(newBot, isExisting = false)
                showCreateSheet = false
            }
        )
    }
}

@Composable
private fun ParameterSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            Text(valueText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CloudFlashCobalt)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MoteNavy,
                activeTrackColor = CloudFlashCobalt,
                inactiveTrackColor = Color(0xFFE2E8F0)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePersonaBottomSheet(
    onDismiss: () -> Unit,
    onCreate: (BotPersona) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var selectedColor by remember { mutableLongStateOf(personaAccentPalette.first()) }
    var selectedMode by remember { mutableStateOf(MoteInferenceMode.PREFER_ON_DEVICE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Create Bot Persona", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MoteNavy)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Persona Name") },
                placeholder = { Text("e.g., Code Coach") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persona_name_input")
            )

            OutlinedTextField(
                value = role,
                onValueChange = { role = it.take(80) },
                label = { Text("Short Role") },
                placeholder = { Text("e.g., Kotlin & Architecture Reviewer") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persona_role_input")
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it.take(1000) },
                label = { Text("System Instruction") },
                placeholder = { Text("Describe how this assistant should respond...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persona_prompt_input")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Accent:", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = SlateMuted)
                personaAccentPalette.forEach { hex ->
                    val chosen = hex == selectedColor
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(
                                width = if (chosen) 2.5.dp else 0.dp,
                                color = if (chosen) CloudFlashCobalt else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { selectedColor = hex }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    MoteInferenceMode.PREFER_ON_DEVICE to "Hybrid",
                    MoteInferenceMode.PREFER_IN_CLOUD to "Cloud"
                ).forEach { (mode, title) ->
                    val picked = selectedMode == mode
                    FilterChip(
                        selected = picked,
                        onClick = { selectedMode = mode },
                        label = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            val canSave = name.isNotBlank() && role.isNotBlank() && systemPrompt.isNotBlank()
            Button(
                onClick = {
                    val cleanId = name.trim().lowercase(Locale.US)
                        .replace(Regex("[^a-z0-9]+"), "_")
                        .trim('_')
                        .take(40)
                        .ifBlank { "persona_${System.currentTimeMillis()}" }
                    onCreate(
                        BotPersona(
                            id = "${cleanId}_${(System.currentTimeMillis() % 10000)}",
                            name = name.trim(),
                            role = role.trim(),
                            colorHex = selectedColor,
                            active = true,
                            inferenceMode = selectedMode.name,
                            systemPrompt = systemPrompt.trim()
                        )
                    )
                },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("save_persona_button")
            ) {
                Text("Save Persona", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
