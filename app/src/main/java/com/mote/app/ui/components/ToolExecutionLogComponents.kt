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
import com.mote.app.data.db.ToolChainStateEntity
import com.mote.app.service.LiveVoiceRelayUiState
import com.mote.app.service.VoiceRelayPhase
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * Chronological, verifiable 'ToolExecutionLog' embedded inside the Chat Hub history stream.
 * Displays sequential multi-step tool calls with individual status icons (Pending, Executing,
 * Verified, Blocked, Failed), live step progress, latency telemetry, and EV-XXXXXX receipts.
 */
@Composable
fun ToolExecutionLog(
    chains: List<ToolChainExecutionSnapshot>,
    activeChain: ToolChainExecutionSnapshot?,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier,
    persistedStates: List<ToolChainStateEntity> = emptyList()
) {
    ToolExecutionLogHistoryPanel(
        chains = chains,
        activeChain = activeChain,
        onClearLog = onClearLog,
        modifier = modifier.testTag("tool_execution_log_root"),
        persistedStates = persistedStates
    )
}

@Composable
fun ToolExecutionLogHistoryPanel(
    chains: List<ToolChainExecutionSnapshot>,
    activeChain: ToolChainExecutionSnapshot?,
    onClearLog: () -> Unit,
    modifier: Modifier = Modifier,
    persistedStates: List<ToolChainStateEntity> = emptyList()
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
                                val totalSteps = chain.steps.size.coerceAtLeast(1)
                                val doneSteps = chain.steps.count {
                                    it.status == ToolStepStatus.EXECUTION_VERIFIED ||
                                        it.status == ToolStepStatus.FAILED ||
                                        it.status == ToolStepStatus.BLOCKED
                                }
                                LinearProgressIndicator(
                                    progress = { (doneSteps.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f) },
                                    color = chainTint,
                                    trackColor = MoteBorder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape)
                                )
                                chain.steps.forEach { step ->
                                    val (statusColor, statusIcon, statusDisplayLabel) = when (step.status) {
                                        ToolStepStatus.EXECUTION_VERIFIED -> Triple(LocalNanoEmerald, Icons.Outlined.CheckCircle, "Verified")
                                        ToolStepStatus.RUNNING -> Triple(CloudFlashCobalt, Icons.Outlined.Sync, "Executing")
                                        ToolStepStatus.QUEUED -> Triple(SlateMuted, Icons.Outlined.HourglassEmpty, "Pending")
                                        ToolStepStatus.BLOCKED -> Triple(Color(0xFFD97706), Icons.Outlined.Block, "Blocked")
                                        ToolStepStatus.FAILED -> Triple(Color(0xFFDC2626), Icons.Outlined.ErrorOutline, "Failed")
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
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(statusColor.copy(alpha = 0.14f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = statusDisplayLabel,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = statusColor
                                                )
                                            }
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

                                val chainPersistedStates = persistedStates.filter {
                                    it.chainId == chain.chainId && !it.outputKey.startsWith("step_")
                                }
                                if (chainPersistedStates.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(tokens.accentBlueBg.copy(alpha = 0.7f))
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = "PERSISTED TOOLCHAINSTATE (ROOM)",
                                            fontSize = 8.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = tokens.accentBlue
                                        )
                                        chainPersistedStates.take(5).forEach { st ->
                                            val consumerLabel = st.consumedByStepIndex?.let { " → Step #$it" } ?: ""
                                            Text(
                                                text = "Step #${st.stepIndex} ${st.outputKey}=${st.outputValue.take(42)}$consumerLabel",
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MoteNavy,
                                                maxLines = 1,
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
                                        text = note.sourceTag,
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

/**
 * Inline Generative UI card for Android ClipboardManager tool operations (read, write, clear, paste).
 */
@Composable
fun InChatClipboardWidget(
    part: InChatUiPart.ClipboardPanel,
    onCopyAgain: (String) -> Unit,
    onPasteToScreen: (String) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalMoteColors.current
    val accent = Color(0xFF0EA5E9)

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) accent.copy(alpha = 0.45f) else Color(0xFFDC2626).copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_clipboard_widget")
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
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentPaste,
                    contentDescription = "System Clipboard Manager",
                    tint = if (part.success) accent else Color(0xFFDC2626),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CLIPBOARD MANAGER",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        val textBody = part.clipboardText?.takeIf { it.isNotBlank() }
        if (textBody != null) {
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
                    text = textBody,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MoteNavy,
                    lineHeight = 16.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onCopyAgain(textBody)
                        Toast.makeText(context, "Copied to system clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("inchat_clipboard_copy_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy text to clipboard",
                        tint = MoteNavy,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Copy", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                }
                Button(
                    onClick = { onPasteToScreen(textBody) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("inchat_clipboard_paste_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentPasteGo,
                        contentDescription = "Paste into focused field",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Paste Live", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/**
 * Interactive Phone Dialer card inside Chat Hub (Google Play compliant: uses ACTION_DIAL + optional
 * Accessibility live-cursor Call button click, SMS composer, and live STT<->TTS Voice Relay).
 */
@Composable
fun InChatPhoneDialerWidget(
    part: InChatUiPart.PhoneDialerPanel,
    onOpenDialer: (number: String, label: String?) -> Unit,
    onClickCallButton: (number: String) -> Unit,
    onSendSms: (number: String, body: String) -> Unit,
    onStartVoiceRelay: (targetLabel: String) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalMoteColors.current
    val accent = LocalNanoEmerald

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) accent.copy(alpha = 0.45f) else Color(0xFFDC2626).copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_phone_dialer_widget")
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
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Phone,
                    contentDescription = "Phone Dialer Tool",
                    tint = if (part.success) accent else Color(0xFFDC2626),
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "PHONE DIALER & CALL",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        if (part.phoneNumber.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoteSurfaceSubtle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.phoneNumber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = MoteNavy
                    )
                    if (!part.contactLabel.isNullOrBlank()) {
                        Text(part.contactLabel, fontSize = 11.sp, color = SlateMuted)
                    }
                }
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Phone Number", part.phoneNumber))
                        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp).testTag("inchat_phone_copy_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy phone number",
                        tint = SlateMuted,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        if (part.detailLines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                part.detailLines.take(4).forEach { line ->
                    Text("• $line", fontSize = 10.5.sp, color = SlateMuted)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onOpenDialer(part.phoneNumber, part.contactLabel) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_phone_open_dialer_button")
            ) {
                Icon(Icons.Outlined.Dialpad, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Open Dialer", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Button(
                onClick = { onClickCallButton(part.phoneNumber) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CloudFlashCobalt),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_phone_click_call_button")
            ) {
                Icon(Icons.Outlined.AdsClick, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Click Call", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onSendSms(part.phoneNumber, "") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_phone_sms_button")
            ) {
                Icon(Icons.Outlined.Sms, contentDescription = null, tint = MoteNavy, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Send SMS", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
            OutlinedButton(
                onClick = {
                    val label = part.contactLabel?.takeIf { it.isNotBlank() }
                        ?: part.phoneNumber.ifBlank { "Live Phone Relay" }
                    onStartVoiceRelay("Call $label")
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_phone_voice_relay_button")
            ) {
                Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Live Relay", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
            }
        }
    }
}

/**
 * Interactive Wi-Fi & Network Telemetry Card inside Chat Hub.
 */
@Composable
fun InChatNetworkControlWidget(
    part: InChatUiPart.NetworkStatusPanel,
    onOpenWifiPanel: () -> Unit,
    onOpenInternetPanel: () -> Unit,
    onRunPing: () -> Unit,
    onRefreshNetwork: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val accent = if (part.isConnected) LocalNanoEmerald else Color(0xFFD97706)

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .testTag("inchat_network_control_widget")
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
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (part.isConnected) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    contentDescription = "Wi-Fi and Network Status",
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "WI-FI & NETWORK TOOLS",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MoteSurfaceSubtle)
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = part.wifiSsid?.let { "${part.transportLabel} · $it" } ?: part.transportLabel,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                val metrics = buildList {
                    part.linkSpeedMbps?.let { add("${it} Mbps") }
                    part.localIpv4?.let { add("IP $it") }
                    part.latencyMs?.let { add("Ping ${it}ms") }
                }.joinToString(" · ")
                if (metrics.isNotBlank()) {
                    Text(metrics, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SlateMuted)
                }
            }
            // Signal bars visual indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (bar in 1..4) {
                    val lit = bar <= part.signalBars
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height((6 + bar * 4).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (lit) accent else MoteBorder)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenWifiPanel,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_network_wifi_panel_button")
            ) {
                Icon(Icons.Outlined.Wifi, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Wi-Fi Panel", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            OutlinedButton(
                onClick = onOpenInternetPanel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_network_internet_panel_button")
            ) {
                Text("Internet", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRunPing,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_network_ping_button")
            ) {
                Icon(Icons.Outlined.Speed, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Ping Test", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
            }
            OutlinedButton(
                onClick = onRefreshNetwork,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("inchat_network_refresh_button")
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MoteNavy, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Refresh", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }
    }
}

/**
 * Interactive Camera & Hardware Torch Control Card inside Chat Hub.
 */
@Composable
fun InChatCameraControlWidget(
    part: InChatUiPart.CameraControlPanel,
    onOpenCamera: (mode: String, facing: String?) -> Unit,
    onToggleTorch: (enabled: Boolean?) -> Unit,
    onTriggerShutter: () -> Unit,
    onInspectHardware: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val accent = Color(0xFFEC4899)

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) accent.copy(alpha = 0.45f) else Color(0xFFDC2626).copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_camera_control_widget")
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
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "Camera Control Tool",
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CAMERA & TORCH · ${part.mode.uppercase(Locale.US)}",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        if (part.detailLines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoteSurfaceSubtle)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                part.detailLines.take(5).forEach { line ->
                    Text("• $line", fontSize = 10.5.sp, color = MoteNavy)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { onOpenCamera("photo", "back") },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_camera_photo_button")
            ) {
                Text("Photo", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            OutlinedButton(
                onClick = { onOpenCamera("selfie", "front") },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_camera_selfie_button")
            ) {
                Text("Selfie", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
            OutlinedButton(
                onClick = { onOpenCamera("video", "back") },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_camera_video_button")
            ) {
                Text("Video", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { onToggleTorch(null) },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_camera_torch_button")
            ) {
                Icon(
                    imageVector = if (part.torchEnabled) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                    contentDescription = "Toggle flashlight",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (part.torchEnabled) "Torch Off" else "Torch On", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
            Button(
                onClick = onTriggerShutter,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalNanoEmerald),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_camera_shutter_button")
            ) {
                Icon(Icons.Outlined.Camera, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Shutter", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            OutlinedButton(
                onClick = onInspectHardware,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("inchat_camera_specs_button")
            ) {
                Text("Specs", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
            }
        }
    }
}

/**
 * Interactive Application Control & Live Cursor Hover Card inside Chat Hub.
 */
@Composable
fun InChatAppCursorControlWidget(
    part: InChatUiPart.AppControlPanel,
    onHoverAndClickTarget: (target: String) -> Unit,
    onSwipeScreen: (direction: String) -> Unit,
    onPreviewCursor: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val accent = Color(0xFF0EA5E9)

    MoteCard(
        background = tokens.surface,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (part.success) accent.copy(alpha = 0.45f) else Color(0xFFD97706).copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("inchat_app_cursor_control_widget")
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
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AdsClick,
                    contentDescription = "Live Cursor App Control",
                    tint = accent,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "LIVE CURSOR APP CONTROL",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (part.success) Color(0xFFDCFCE7) else Color(0xFFFEF3C7))
                            .padding(horizontal = 6.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = part.badge,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (part.success) Color(0xFF15803D) else Color(0xFFB45309)
                        )
                    }
                }
                Text(part.title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                Text(part.summary, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
            }
        }

        if (part.cursorX != null && part.cursorY != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Cursor Telemetry: (${part.cursorX.toInt()}, ${part.cursorY.toInt()}) px${part.targetLabel?.let { " · Target: $it" } ?: ""}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
        }

        if (part.interactiveElements.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TAP ANY DETECTED ELEMENT TO HOVER & CLICK",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = SlateMuted
            )
            Spacer(Modifier.height(5.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                part.interactiveElements.take(6).forEach { elem ->
                    val targetName = elem.substringBefore(" [").trim().ifBlank { elem }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MoteSurfaceSubtle)
                            .border(0.6.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(9.dp))
                            .clickable { onHoverAndClickTarget(targetName) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = elem,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MoteNavy,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Outlined.TouchApp,
                            contentDescription = "Hover and click $targetName",
                            tint = accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!part.targetLabel.isNullOrBlank()) {
                Button(
                    onClick = { onHoverAndClickTarget(part.targetLabel) },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_cursor_reclick_button")
                ) {
                    Text("Hover & Click", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            OutlinedButton(
                onClick = { onSwipeScreen("down") },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("inchat_cursor_scroll_down_button")
            ) {
                Text("Scroll Down", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
            OutlinedButton(
                onClick = onPreviewCursor,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("inchat_cursor_test_button")
            ) {
                Text("Test", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = accent)
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
