package com.mote.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.InChatUiPart
import com.mote.app.ai.ToolChainExecutionSnapshot
import com.mote.app.ai.ToolStepStatus
import com.mote.app.data.db.SavedNoteEntity
import com.mote.app.service.LiveVoiceRelayUiState
import com.mote.app.service.VoiceRelayPhase
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Chronological, verifiable 'Tool Execution Log' embedded inside the Chat Hub history stream.
 * Surfaces every triggered multi-step tool chain (live + persisted in Room) with expandable
 * atomic sub-step status icons (Pending, Executing, Verified, Blocked, Failed), intermediate
 * state piping badges, latency telemetry, background verification proof, and EV-XXXXXX receipts.
 */
@Composable
fun ToolExecutionLogHistoryPanel(
    chains: List<ToolChainExecutionSnapshot>,
    activeChain: ToolChainExecutionSnapshot?,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalMoteColors.current
    val mergedChains = remember(chains, activeChain) {
        val byId = linkedMapOf<String, ToolChainExecutionSnapshot>()
        if (activeChain != null && !activeChain.isComplete) {
            byId[activeChain.chainId] = activeChain
        }
        chains.forEach { snap ->
            if (!byId.containsKey(snap.chainId)) {
                byId[snap.chainId] = snap
            }
        }
        byId.values.toList()
    }
    if (mergedChains.isEmpty()) return

    var expandedPanel by remember { mutableStateOf(activeChain != null && !activeChain.isComplete) }
    var expandedChainId by remember(mergedChains.firstOrNull()?.chainId) {
        mutableStateOf(mergedChains.firstOrNull()?.chainId)
    }

    LaunchedEffect(activeChain?.chainId, activeChain?.isComplete) {
        if (activeChain != null && !activeChain.isComplete) {
            expandedPanel = true
            expandedChainId = activeChain.chainId
        }
    }

    val totalVerifiedSteps = remember(mergedChains) {
        mergedChains.sumOf { it.verifiedCount }
    }
    val hasRunning = mergedChains.any { !it.isComplete }

    MoteCard(
        background = tokens.surface,
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (hasRunning) CloudFlashCobalt.copy(alpha = 0.55f) else LocalNanoEmerald.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("chat_tool_execution_log_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expandedPanel = !expandedPanel }
                .padding(vertical = 2.dp)
                .testTag("chat_tool_execution_log_toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasRunning) tokens.accentBlueBg
                        else if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (hasRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CloudFlashCobalt
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.FactCheck,
                        contentDescription = "Tool Execution Log",
                        tint = LocalNanoEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Tool Execution Log",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (hasRunning) tokens.accentBlueBg
                                else if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFDCFCE7)
                            )
                            .padding(horizontal = 7.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = if (hasRunning) "LIVE" else "$totalVerifiedSteps VERIFIED",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (hasRunning) tokens.accentBlue
                            else if (tokens.isDark) Color(0xFF6EE7B7) else Color(0xFF15803D)
                        )
                    }
                }
                Text(
                    text = "${mergedChains.size} chronological chain(s) · Tap to ${if (expandedPanel) "collapse" else "inspect audit trail"}",
                    fontSize = 11.sp,
                    color = SlateMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expandedPanel) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expandedPanel) "Collapse log" else "Expand log",
                tint = SlateMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        AnimatedVisibility(
            visible = expandedPanel,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                mergedChains.take(8).forEach { chain ->
                    val isExpanded = expandedChainId == chain.chainId
                    val chainTint = when {
                        !chain.isComplete -> CloudFlashCobalt
                        chain.allVerified -> LocalNanoEmerald
                        chain.verifiedCount > 0 -> Color(0xFFD97706)
                        else -> Color(0xFFDC2626)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MoteSurfaceSubtle)
                            .border(0.75.dp, chainTint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .testTag("tool_log_chain_${chain.chainId}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedChainId = if (isExpanded) null else chain.chainId
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    !chain.isComplete -> Icons.Outlined.Sync
                                    chain.allVerified -> Icons.Outlined.VerifiedUser
                                    chain.verifiedCount > 0 -> Icons.Outlined.WarningAmber
                                    else -> Icons.Outlined.ErrorOutline
                                },
                                contentDescription = null,
                                tint = chainTint,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = chain.chainTitle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = buildString {
                                    append(formatExecutionLogTimestamp(chain.startedAtEpochMs))
                                    append(" · ${chain.verifiedCount}/${chain.steps.size} verified")
                                    if (chain.promptSummary.isNotBlank()) {
                                        append(" · \"${chain.promptSummary.take(44)}\"")
                                    }
                                }
                                Text(
                                    text = subtitle,
                                    fontSize = 10.sp,
                                    color = SlateMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Toggle chain steps",
                                tint = SlateMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chain.steps.forEach { step ->
                                    val (statusColor, statusIcon) = when (step.status) {
                                        ToolStepStatus.EXECUTION_VERIFIED -> LocalNanoEmerald to Icons.Outlined.CheckCircle
                                        ToolStepStatus.RUNNING -> CloudFlashCobalt to Icons.Outlined.Sync
                                        ToolStepStatus.QUEUED -> SlateMuted to Icons.Outlined.Schedule
                                        ToolStepStatus.BLOCKED -> Color(0xFFD97706) to Icons.Outlined.Block
                                        ToolStepStatus.FAILED -> Color(0xFFDC2626) to Icons.Outlined.Cancel
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(tokens.surface)
                                            .border(0.5.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (step.status == ToolStepStatus.RUNNING) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(13.dp),
                                                    strokeWidth = 1.8.dp,
                                                    color = statusColor
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = statusIcon,
                                                    contentDescription = step.status.label,
                                                    tint = statusColor,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Text(
                                                text = "#${step.stepIndex} ${step.operationLabel}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MoteNavy,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = step.status.label,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = statusColor
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = step.engineLabel,
                                                fontSize = 9.5.sp,
                                                color = SlateMuted
                                            )
                                            val meta = buildString {
                                                step.receiptId?.let { append(it) }
                                                if (step.latencyMs > 0L) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append("${step.latencyMs}ms")
                                                }
                                            }
                                            if (meta.isNotBlank()) {
                                                Text(
                                                    text = meta,
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (step.receiptId != null) LocalNanoEmerald else SlateMuted
                                                )
                                            }
                                        }
                                        if (!step.usedIntermediateState.isNullOrBlank()) {
                                            Text(
                                                text = "↪ ${step.usedIntermediateState}",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = tokens.accentBlue,
                                                modifier = Modifier.padding(top = 3.dp)
                                            )
                                        }
                                        if (!step.verificationDetail.isNullOrBlank()) {
                                            Text(
                                                text = "✓ ${step.verificationDetail}",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (step.status == ToolStepStatus.EXECUTION_VERIFIED) LocalNanoEmerald else Color(0xFFD97706),
                                                modifier = Modifier.padding(top = 2.dp),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onClearLog,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .testTag("chat_tool_execution_log_clear")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear tool execution log",
                            tint = SlateMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Clear Log", fontSize = 11.sp, color = SlateMuted)
                    }
                }
            }
        }
    }
}

/**
 * Displays OCR extracted text inside Chat Hub with one-tap Save to Notes and Copy controls.
 */
@Composable
fun InChatOcrResultWidget(
    part: InChatUiPart.OcrResultPanel,
    onSaveToNotes: (title: String, text: String) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalMoteColors.current
    var savedOnce by remember(part.partId) { mutableStateOf(false) }

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) Color(0xFF7C3AED).copy(alpha = 0.4f) else Color(0xFFDC2626).copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_ocr_result_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C3AED).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.DocumentScanner,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VISION OCR ANALYSIS · ${part.sourceLabel.uppercase(Locale.US)}",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF7C3AED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MoteSurfaceSubtle)
                .border(0.5.dp, MoteBorder, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text(
                text = part.extractedText.ifBlank { "(No text detected)" },
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MoteNavy,
                lineHeight = 16.sp,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (part.success && part.extractedText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSaveToNotes(part.title.ifBlank { "OCR Capture Note" }, part.extractedText)
                        savedOnce = true
                    },
                    enabled = !savedOnce,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("inchat_ocr_save_note_button")
                ) {
                    Icon(
                        imageVector = if (savedOnce) Icons.Outlined.Check else Icons.Outlined.NoteAdd,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (savedOnce) "Saved to Notes" else "Save to Notes",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Mote OCR", part.extractedText))
                        Toast.makeText(context, "OCR text copied", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("inchat_ocr_copy_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy OCR text",
                        tint = MoteNavy,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", fontSize = 11.5.sp, color = MoteNavy)
                }
            }
        }
    }
}

/**
 * Displays Room-persisted Notes created by multi-step OCR -> Notes chains or user note commands.
 */
@Composable
fun InChatNotesWidget(
    part: InChatUiPart.NotesPanel,
    notes: List<SavedNoteEntity>,
    onDeleteNote: (String) -> Unit
) {
    val tokens = LocalMoteColors.current
    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) LocalNanoEmerald.copy(alpha = 0.45f) else Color(0xFFDC2626).copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_notes_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = LocalNanoEmerald,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MOTE NOTES VAULT · ${notes.size}",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalNanoEmerald
                )
                Text(part.actionSummary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (notes.isEmpty()) {
            Text(
                text = "No saved notes yet.",
                fontSize = 12.sp,
                color = SlateMuted,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                notes.take(6).forEach { note ->
                    val highlighted = note.id == part.highlightNoteId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (highlighted) {
                                    if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
                                } else {
                                    MoteSurfaceSubtle
                                }
                            )
                            .border(
                                width = if (highlighted) 1.dp else 0.5.dp,
                                color = if (highlighted) LocalNanoEmerald else MoteBorder,
                                shape = RoundedCornerShape(11.dp)
                            )
                            .padding(10.dp)
                            .testTag("inchat_note_row_${note.id}"),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = note.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(tokens.accentBlueBg)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = note.source,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.accentBlue
                                    )
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = note.content,
                                fontSize = 11.5.sp,
                                color = SlateMuted,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp
                            )
                        }
                        IconButton(
                            onClick = { onDeleteNote(note.id) },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("inchat_note_delete_${note.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete note",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Interactive real-time Google STT <-> Gemini AI <-> Android TTS Voice Call Relay card in Chat Hub.
 */
@Composable
fun InChatVoiceCallRelayWidget(
    part: InChatUiPart.VoiceCallRelayPanel,
    relayState: LiveVoiceRelayUiState,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onSubmitHeardText: (String) -> Unit
) {
    var manualCallerInput by remember { mutableStateOf("") }
    val infiniteTransition = rememberInfiniteTransition(label = "voiceRelayWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val phaseColor = when (relayState.phase) {
        VoiceRelayPhase.LISTENING -> Color(0xFF38BDF8)
        VoiceRelayPhase.THINKING -> Color(0xFFA78BFA)
        VoiceRelayPhase.SPEAKING -> Color(0xFF34D399)
        VoiceRelayPhase.PAUSED -> Color(0xFFFBBF24)
        VoiceRelayPhase.IDLE -> Color(0xFF94A3B8)
    }

    MoteCard(
        background = Color(0xFF0B1120),
        modifier = Modifier
            .border(
                width = 1.2.dp,
                color = if (relayState.isActive) phaseColor.copy(alpha = 0.7f) else Color(0xFF334155),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_voice_call_relay_widget")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0EA5E9), Color(0xFF8B5CF6))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.RecordVoiceOver,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LIVE STT ↔ AI ↔ TTS RELAY",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = phaseColor
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(phaseColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = if (relayState.isActive) relayState.phase.label.uppercase(Locale.US) else "READY",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = phaseColor
                        )
                    }
                }
                Text(
                    text = if (relayState.isActive) relayState.targetLabel else part.title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (relayState.isActive) relayState.statusNote else part.summary,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
            }
        }

        // Animated Real-Time Audio Level Waveform
        if (relayState.isActive) {
            Spacer(Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
            ) {
                val barCount = 24
                val gap = 4.dp.toPx()
                val totalGap = gap * (barCount + 1)
                val barWidth = ((size.width - totalGap) / barCount).coerceAtLeast(2f)
                val level = when (relayState.phase) {
                    VoiceRelayPhase.LISTENING -> relayState.rmsLevel.coerceIn(0.18f, 1f)
                    VoiceRelayPhase.SPEAKING -> 0.72f
                    VoiceRelayPhase.THINKING -> 0.42f
                    else -> 0.12f
                }
                for (i in 0 until barCount) {
                    val wave = abs(sin(wavePhase + i * 0.42f))
                    val barHeight = (size.height * 0.8f * level * (0.35f + 0.65f * wave)).coerceAtLeast(4f)
                    val x = gap + i * (barWidth + gap)
                    val y = (size.height - barHeight) / 2f
                    drawRoundRect(
                        color = phaseColor.copy(alpha = 0.85f),
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }
        }

        if (relayState.partialTranscript.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Hearing: \"${relayState.partialTranscript}\"",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF7DD3FC)
            )
        }

        relayState.errorMessage?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = err,
                fontSize = 11.sp,
                color = Color(0xFFFCA5A5)
            )
        }

        if (relayState.turns.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                relayState.turns.takeLast(5).forEach { turn ->
                    val isAi = turn.speaker.contains("Mote", ignoreCase = true)
                    Column {
                        Text(
                            text = turn.speaker,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAi) Color(0xFF34D399) else Color(0xFF38BDF8)
                        )
                        Text(
                            text = turn.text,
                            fontSize = 11.5.sp,
                            color = Color.White,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        if (relayState.isActive) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTogglePause,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (relayState.phase == VoiceRelayPhase.PAUSED) LocalNanoEmerald else Color(0xFFF59E0B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("inchat_voice_relay_pause_button")
                ) {
                    Text(
                        text = if (relayState.phase == VoiceRelayPhase.PAUSED) "Resume Listening" else "Pause Mic",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 42.dp)
                        .testTag("inchat_voice_relay_stop_button")
                ) {
                    Text("End Relay", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = manualCallerInput,
                    onValueChange = { manualCallerInput = it },
                    placeholder = {
                        Text(
                            "Or type caller speech for instant AI TTS reply...",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val t = manualCallerInput.trim()
                        if (t.isNotEmpty()) {
                            manualCallerInput = ""
                            onSubmitHeardText(t)
                        }
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("inchat_voice_relay_manual_input"),
                    shape = RoundedCornerShape(10.dp)
                )
                FilledIconButton(
                    onClick = {
                        val t = manualCallerInput.trim()
                        if (t.isNotEmpty()) {
                            manualCallerInput = ""
                            onSubmitHeardText(t)
                        }
                    },
                    enabled = manualCallerInput.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color(0xFF0EA5E9),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("inchat_voice_relay_manual_send")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Send utterance to voice relay",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun formatExecutionLogTimestamp(epochMs: Long): String {
    return try {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
    } catch (_: Exception) {
        "Just now"
    }
}
