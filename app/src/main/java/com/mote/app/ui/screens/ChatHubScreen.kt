package com.mote.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.InferenceMetrics
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import com.mote.app.ui.AiCardUiState
import com.mote.app.ui.BotsUiState
import com.mote.app.ui.ChatHistoryUiState
import com.mote.app.ui.ChatStreamUiState
import com.mote.app.ui.MoteViewModel
import com.mote.app.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class StarterPrompt(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val useGenUi: Boolean = false
)

private val starterPrompts = listOf(
    StarterPrompt(
        title = "Focus Controls",
        subtitle = "Generate live brightness, DND & timer widgets",
        prompt = "Focus mode: dim screen to 30%, enable DND, start 25 minute timer",
        useGenUi = true
    ),
    StarterPrompt(
        title = "Quick Summary",
        subtitle = "Turn messy notes into clear action bullets",
        prompt = "Give me a crisp 3-bullet daily planning checklist I can follow right now."
    ),
    StarterPrompt(
        title = "Explain Simply",
        subtitle = "Break down a complex concept in plain language",
        prompt = "Explain how on-device LLMs and cloud LLMs complement each other on Android in 4 short sentences."
    ),
    StarterPrompt(
        title = "Smart Reply Drafts",
        subtitle = "Craft concise, natural message replies",
        prompt = "Draft 3 short, friendly replies telling a teammate I'm wrapping up and will share the update in 10 minutes."
    )
)

@Composable
fun ChatHubScreen(
    viewModel: MoteViewModel,
    isGuest: Boolean,
    isImeVisible: Boolean,
    floatingAssistantVisible: Boolean,
    onToggleFloatingAssistant: () -> Unit,
    onOpenModelsTab: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val botsState by viewModel.botsState.collectAsStateWithLifecycle()
    val chatHistoryState by viewModel.chatHistoryState.collectAsStateWithLifecycle()
    val chatStreamState by viewModel.chatStreamState.collectAsStateWithLifecycle()
    val aiCardState by viewModel.aiState.collectAsStateWithLifecycle()
    val llmConfig by viewModel.llmConfig.collectAsStateWithLifecycle()
    val selectedBotId by viewModel.selectedBotId.collectAsStateWithLifecycle()
    val lastMetrics by viewModel.lastMetrics.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()

    val executor = remember(context) { DeviceActionExecutor(context) }
    val bots = (botsState as? BotsUiState.Success)?.bots.orEmpty()
    val selectedBot = bots.firstOrNull { it.id == selectedBotId }
        ?: bots.firstOrNull { it.active }
        ?: bots.firstOrNull()

    val messages = (chatHistoryState as? ChatHistoryUiState.Success)?.messages.orEmpty()
    val isStreaming = chatStreamState is ChatStreamUiState.Streaming
    val isCardLoading = aiCardState is AiCardUiState.Loading
    val listState = rememberLazyListState()

    var composerText by remember { mutableStateOf("") }
    var genUiMode by remember { mutableStateOf(false) }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val decoded = decodeSampledBitmap(context, uri, maxDimension = 1024)
                if (decoded != null) {
                    attachedBitmap = decoded
                } else {
                    Toast.makeText(context, "Could not load selected image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Smooth auto-scroll whenever messages grow or streaming chunks arrive
    val streamTextLen = (chatStreamState as? ChatStreamUiState.Streaming)?.partialText?.length ?: 0
    LaunchedEffect(messages.size, streamTextLen, aiCardState is AiCardUiState.Success) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    val activeModelMeta = HybridAiRouter.supportedModels.firstOrNull { it.id == llmConfig.modelName }
        ?: HybridAiRouter.supportedModels.first()

    fun handleSend() {
        val prompt = composerText.trim()
        if (prompt.isEmpty() && attachedBitmap == null) return
        val imageToSend = attachedBitmap
        composerText = ""
        attachedBitmap = null
        if (genUiMode && imageToSend == null) {
            viewModel.generateFocusControlCard(
                userPrompt = prompt,
                botSystemPrompt = selectedBot?.systemPrompt ?: "Return compact Android controls.",
                botId = selectedBot?.id ?: "focus",
                botName = selectedBot?.name ?: "Focus"
            )
        } else {
            viewModel.sendStreamingChatMessage(
                prompt = prompt,
                bot = selectedBot,
                attachedBitmap = imageToSend
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IosCanvas)
            .imePadding()
    ) {
        // LLM Client Top Bar (Model selector pill + Sync badge + Floating Orb + Clear)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas.copy(alpha = 0.95f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0x160F172A), RoundedCornerShape(999.dp))
                    .clickable(onClick = onOpenModelsTab)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("chat_model_selector_pill"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(LocalNanoEmerald)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeModelMeta.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${selectedBot?.name ?: "Mote"} · ${if (isGuest) "Local Guest" else "Firestore Sync"}",
                        fontSize = 10.5.sp,
                        color = SlateMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = "Switch model",
                    tint = CloudFlashCobalt,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(
                onClick = onToggleFloatingAssistant,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("toggle_floating_assistant_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.BubbleChart,
                    contentDescription = if (floatingAssistantVisible) "Hide floating orb" else "Show floating orb",
                    tint = if (floatingAssistantVisible) LocalNanoEmerald else SlateMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (messages.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.clearChatHistory() },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("chat_clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "New chat",
                        tint = SlateMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Persona quick-switcher row (inspired by multi-persona LLM clients)
        if (bots.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bots.forEach { bot ->
                    val isSelected = bot.id == selectedBot?.id
                    val accent = Color(bot.colorHex)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isSelected) MoteNavy else Color.White)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) MoteNavy else Color(0x160F172A),
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { viewModel.selectBot(bot.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                            .testTag("chat_persona_chip_${bot.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) LocalNanoEmerald else accent)
                        )
                        Text(
                            text = bot.name,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else MoteNavy
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Main Chat Message Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actionError?.let { msg ->
                item(key = "action_error") {
                    MoteCard(background = Color(0xFFFEF2F2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, fontSize = 12.sp, color = Color(0xFFB91C1C), modifier = Modifier.weight(1f))
                            TextButton(onClick = { viewModel.clearActionError() }) {
                                Text("Dismiss", color = MoteNavy, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (chatHistoryState is ChatHistoryUiState.Loading) {
                item(key = "history_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MoteNavy, strokeWidth = 2.5.dp)
                    }
                }
            } else if (messages.isEmpty() && !isStreaming && aiCardState !is AiCardUiState.Success) {
                item(key = "empty_welcome") {
                    ChatWelcomeHero(
                        modelName = activeModelMeta.displayName,
                        personaName = selectedBot?.name ?: "Mote",
                        onStarterClick = { starter ->
                            if (starter.useGenUi) {
                                viewModel.generateFocusControlCard(
                                    userPrompt = starter.prompt,
                                    botSystemPrompt = selectedBot?.systemPrompt
                                        ?: "Return compact Android Focus controls.",
                                    botId = selectedBot?.id ?: "focus",
                                    botName = selectedBot?.name ?: "Focus"
                                )
                            } else {
                                viewModel.sendStreamingChatMessage(
                                    prompt = starter.prompt,
                                    bot = selectedBot
                                )
                            }
                        }
                    )
                }
            }

            items(messages, key = { it.id }) { msg ->
                ChatMessageBubble(
                    message = msg,
                    onCopy = { text -> copyToClipboard(context, text) },
                    onReplay = { prompt -> composerText = prompt }
                )
            }

            // Live streaming response bubble
            if (chatStreamState is ChatStreamUiState.Streaming) {
                val streaming = chatStreamState as ChatStreamUiState.Streaming
                item(key = "live_streaming_bubble") {
                    StreamingAssistantBubble(
                        botName = streaming.botName,
                        partialText = streaming.partialText,
                        elapsedMs = streaming.elapsedMs,
                        onStop = { viewModel.stopStreaming() }
                    )
                }
            }

            // Stream error card
            if (chatStreamState is ChatStreamUiState.Error) {
                val err = chatStreamState as ChatStreamUiState.Error
                item(key = "stream_error_card") {
                    MoteCard(background = Color(0xFFFEF2F2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Response Error", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB91C1C))
                                Spacer(Modifier.height(2.dp))
                                Text(err.message, fontSize = 12.sp, color = Color(0xFF7F1D1D))
                            }
                            TextButton(onClick = { viewModel.clearChatStreamError() }) {
                                Text("Dismiss", color = MoteNavy, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Inline Generative UI Control Card (when generated)
            when (val cardState = aiCardState) {
                is AiCardUiState.Loading -> {
                    item(key = "genui_loading") {
                        MoteCard(background = Color.White) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = CloudFlashCobalt,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Building interactive Android controls...",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                            }
                        }
                    }
                }
                is AiCardUiState.Success -> {
                    item(key = "genui_card_result") {
                        GenUiCardRenderer(result = cardState.result) { action ->
                            executor.execute(action)
                        }
                    }
                }
                is AiCardUiState.Error -> {
                    item(key = "genui_error") {
                        MoteCard(background = Color(0xFFFEF2F2)) {
                            Text("Control Card Error", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB91C1C))
                            Spacer(Modifier.height(4.dp))
                            Text(cardState.message, fontSize = 12.sp, color = Color(0xFF7F1D1D))
                        }
                    }
                }
                else -> Unit
            }

            // Latest inference telemetry strip (tokens/sec & TTFT like local-llms-on-android)
            lastMetrics?.let { metrics ->
                if (!isStreaming) {
                    item(key = "inference_metrics_strip") {
                        InferenceMetricsChip(metrics = metrics)
                    }
                }
            }
        }

        // Composer dock (sits cleanly above the floating bottom nav when keyboard is closed)
        val bottomDockClearance = if (isImeVisible) 8.dp else 82.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCanvas)
                .then(if (!isImeVisible) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomDockClearance),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Optional attached image thumbnail preview
            AnimatedVisibility(
                visible = attachedBitmap != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                attachedBitmap?.let { bmp ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0x160F172A), RoundedCornerShape(14.dp))
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Attached image preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Text(
                            text = "Image ready for vision",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MoteNavy,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { attachedBitmap = null },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove image",
                                tint = SlateMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Sleek pill composer bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = Color(0x140B0D1D))
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0x1A0F172A), RoundedCornerShape(26.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Multimodal Photo Picker button (zero storage permissions!)
                IconButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chat_attach_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = "Attach image",
                        tint = if (attachedBitmap != null) LocalNanoEmerald else SlateMuted,
                        modifier = Modifier.size(21.dp)
                    )
                }

                // Toggle between Conversational Chat vs Generative UI Control Card mode
                IconButton(
                    onClick = { genUiMode = !genUiMode },
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("chat_genui_mode_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = if (genUiMode) "GenUI Card mode active" else "Switch to GenUI Card mode",
                        tint = if (genUiMode) CloudFlashCobalt else SlateMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                ) {
                    if (composerText.isEmpty()) {
                        Text(
                            text = if (genUiMode) "Ask for Android controls..." else "Message ${selectedBot?.name ?: "Mote"}...",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        textStyle = TextStyle(
                            color = MoteNavy,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(CloudFlashCobalt),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { handleSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_composer_input")
                    )
                }

                if (isStreaming) {
                    FilledIconButton(
                        onClick = { viewModel.stopStreaming() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_stop_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "Stop generating",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    val canSend = (composerText.isNotBlank() || attachedBitmap != null) && !isCardLoading
                    FilledIconButton(
                        onClick = { handleSend() },
                        enabled = canSend,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (genUiMode) CloudFlashCobalt else MoteNavy,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE2E8F0),
                            disabledContentColor = Color(0xFF94A3B8)
                        ),
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("chat_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send message",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatWelcomeHero(
    modelName: String,
    personaName: String,
    onStarterClick: (StarterPrompt) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(MoteNavy, Color(0xFF172042))
                    )
                )
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = LocalNanoEmerald,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "$modelName · $personaName",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF94A3B8)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "What would you like to build or automate?",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 24.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Stream multimodal answers, attach photos for vision Q&A, or switch to Controls mode for instant Android widgets.",
                fontSize = 12.5.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 18.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starterPrompts.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { starter ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0x140F172A), RoundedCornerShape(14.dp))
                                .clickable { onStarterClick(starter) }
                                .padding(12.dp)
                        ) {
                            Text(
                                text = starter.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (starter.useGenUi) CloudFlashCobalt else MoteNavy
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = starter.subtitle,
                                fontSize = 11.sp,
                                color = SlateMuted,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onReplay: (String) -> Unit
) {
    val isUser = message.chatRole() == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 18.dp
                    )
                )
                .background(if (isUser) MoteNavy else Color.White)
                .border(
                    width = if (isUser) 0.dp else 1.dp,
                    color = if (isUser) Color.Transparent else Color(0x140F172A),
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 18.dp
                    )
                )
                .clickable(enabled = isUser) { onReplay(message.text) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("chat_bubble_${message.id}")
        ) {
            if (!isUser) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.botName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CloudFlashCobalt
                    )
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy response",
                        tint = SlateMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onCopy(message.text) }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = message.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isUser) Color.White else MoteNavy
            )
        }
    }
}

@Composable
private fun StreamingAssistantBubble(
    botName: String,
    partialText: String,
    elapsedMs: Long,
    onStop: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "streamCursor")
    val cursorAlpha by pulse.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                .background(Color.White)
                .border(1.dp, CloudFlashCobalt.copy(alpha = 0.35f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(cursorAlpha)
                            .clip(CircleShape)
                            .background(LocalNanoEmerald)
                    )
                    Text(
                        text = "$botName · streaming ${(elapsedMs / 1000f).let { String.format(Locale.US, "%.1fs", it) }}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CloudFlashCobalt
                    )
                }
                Text(
                    text = "Stop",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFDC2626),
                    modifier = Modifier.clickable(onClick = onStop)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (partialText.isBlank()) "Thinking..." else "$partialText▍",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (partialText.isBlank()) SlateMuted else MoteNavy
            )
        }
    }
}

@Composable
private fun InferenceMetricsChip(metrics: InferenceMetrics) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFE2E8F0).copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = String.format(
                Locale.US,
                "%.1f tok/s · TTFT %dms · %dms · %s",
                metrics.approxTokensPerSec,
                metrics.ttftMs,
                metrics.latencyMs,
                metrics.modelName
            ),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF475569)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Mote response", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private suspend fun decodeSampledBitmap(
    context: Context,
    uri: Uri,
    maxDimension: Int
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return@withContext null

        var sampleSize = 1
        while (rawW / sampleSize > maxDimension || rawH / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    } catch (_: Exception) {
        null
    }
}
