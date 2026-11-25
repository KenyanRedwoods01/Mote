package com.mote.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.LiteRtDiagnosticLogEntry
import com.mote.app.ai.LiteRtDiagnosticSummary
import com.mote.app.ai.LiteRtLogSeverity
import com.mote.app.ai.LiteRtLogStage
import com.mote.app.ai.StreamParserTelemetrySnapshot
import com.mote.app.ai.TensorShapeDescriptor
import com.mote.app.data.DeviceHardwareSnapshot
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.ui.components.CloudFlashCobalt
import com.mote.app.ui.components.IosCanvas
import com.mote.app.ui.components.LocalNanoEmerald
import com.mote.app.ui.components.MoteBorder
import com.mote.app.ui.components.MoteBrandNavy
import com.mote.app.ui.components.MoteCard
import com.mote.app.ui.components.MoteNavy
import com.mote.app.ui.components.MoteSurface
import com.mote.app.ui.components.MoteSurfaceSubtle
import com.mote.app.ui.components.SlateMuted
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DebugLogFilter(val label: String) {
    ALL("All"),
    ERRORS("Errors"),
    WARNINGS("Warnings"),
    INFERENCE("Inference"),
    ENGINE_INIT("Engine Init")
}

@Composable
fun LiteRtDebugTabContent(
    logs: List<LiteRtDiagnosticLogEntry>,
    summary: LiteRtDiagnosticSummary,
    parserTelemetry: StreamParserTelemetrySnapshot,
    loadedLocalModel: LoadedLocalModelState?,
    hardware: DeviceHardwareSnapshot,
    onRunSelfCheck: () -> Unit,
    onClearLogs: () -> Unit,
    onBuildReportText: () -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeFilter by remember { mutableStateOf(DebugLogFilter.ALL) }
    var expandedTraceIds by remember { mutableStateOf(setOf<String>()) }

    val filteredLogs = remember(logs, activeFilter) {
        when (activeFilter) {
            DebugLogFilter.ALL -> logs
            DebugLogFilter.ERRORS -> logs.filter {
                it.severity == LiteRtLogSeverity.ERROR || it.severity == LiteRtLogSeverity.FATAL
            }
            DebugLogFilter.WARNINGS -> logs.filter { it.severity == LiteRtLogSeverity.WARNING }
            DebugLogFilter.INFERENCE -> logs.filter {
                it.stage == LiteRtLogStage.INFERENCE_STREAM ||
                    it.stage == LiteRtLogStage.CONVERSATION_SETUP ||
                    it.stage == LiteRtLogStage.TENSOR_EXECUTION
            }
            DebugLogFilter.ENGINE_INIT -> logs.filter {
                it.stage == LiteRtLogStage.ENGINE_INIT ||
                    it.stage == LiteRtLogStage.MODEL_INSPECTION ||
                    it.stage == LiteRtLogStage.BACKEND_FALLBACK
            }
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("litert_debug_tab_list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp)
    ) {
        // 1. Dark Telemetry Header Banner (LiteRT-LM Runtime & Tensor Health)
        item(key = "debug_runtime_banner") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(MoteBrandNavy, Color(0xFF151C36), Color(0xFF0F172A))
                        )
                    )
                    .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (summary.errorCount > 0) Color(0x33EF4444) else Color(0x3310B981)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BugReport,
                                    contentDescription = null,
                                    tint = if (summary.errorCount > 0) Color(0xFFFCA5A5) else LocalNanoEmerald,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "LITERT-LM DIAGNOSTICS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF93C5FD),
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = loadedLocalModel?.let { "${it.displayName} · ${it.backend.label}" }
                                        ?: "Engine Idle · ${hardware.primaryAbi}",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (summary.errorCount > 0) Color(0xFF991B1B) else Color(0xFF065F46)
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (summary.errorCount > 0) "${summary.errorCount} ERR" else "HEALTHY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Key diagnostic metrics row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DebugStatTile(
                            label = "EVENTS",
                            value = summary.totalLogs.toString(),
                            accent = Color(0xFF93C5FD),
                            modifier = Modifier.weight(1f)
                        )
                        DebugStatTile(
                            label = "FAILURES",
                            value = summary.errorCount.toString(),
                            accent = if (summary.errorCount > 0) Color(0xFFFCA5A5) else LocalNanoEmerald,
                            modifier = Modifier.weight(1f)
                        )
                        DebugStatTile(
                            label = "WARNINGS",
                            value = summary.warningCount.toString(),
                            accent = Color(0xFFFCD34D),
                            modifier = Modifier.weight(1f)
                        )
                        DebugStatTile(
                            label = "FREE RAM",
                            value = "${hardware.availableRamMb}M",
                            accent = LocalNanoEmerald,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Action bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRunSelfCheck,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalNanoEmerald),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("debug_run_self_check_button")
                        ) {
                            Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(5.dp))
                            Text("Probe Tensors", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard("LiteRT Diagnostic Report", onBuildReportText()) },
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF)),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("debug_copy_report_button")
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy report", modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = onClearLogs,
                            enabled = logs.isNotEmpty() || parserTelemetry.totalChunksProcessed > 0,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x44FFFFFF)),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("debug_clear_logs_button")
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear logs", modifier = Modifier.size(16.dp), tint = Color(0xFFFCA5A5))
                        }
                    }
                }
            }
        }

        // 2. Gemini Partial JSON Stream Parser Telemetry Card
        item(key = "gemini_stream_parser_card") {
            MoteCard(background = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DataObject,
                            contentDescription = null,
                            tint = CloudFlashCobalt,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Gemini Stream JSON Parser",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MoteNavy
                            )
                            Text(
                                text = if (parserTelemetry.lastStreamModel.isNotBlank()) {
                                    "Last stream: ${parserTelemetry.lastStreamModel}"
                                } else {
                                    "Incremental partial-JSON buffering active"
                                },
                                fontSize = 11.sp,
                                color = SlateMuted
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFEFF6FF))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "CHUNK-SAFE",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = CloudFlashCobalt,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LightMetricPill("Streams", parserTelemetry.totalStreamsProcessed.toString(), Modifier.weight(1f))
                    LightMetricPill("Chunks", parserTelemetry.totalChunksProcessed.toString(), Modifier.weight(1f))
                    LightMetricPill("Partial JSON", parserTelemetry.totalPartialJsonChunksBuffered.toString(), Modifier.weight(1f))
                    LightMetricPill("Decoded", parserTelemetry.totalJsonToolBlocksDecoded.toString(), Modifier.weight(1f))
                }
            }
        }

        // 3. Filter Chips Row
        item(key = "debug_filter_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebugLogFilter.entries.forEach { filter ->
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { activeFilter = filter },
                        label = {
                            Text(
                                text = filter.label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LocalMoteColors.current.primaryActionBg,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.testTag("debug_filter_${filter.name.lowercase(Locale.US)}")
                    )
                }
            }
        }

        // 4. Diagnostic Log Entries List (or Empty State)
        if (filteredLogs.isEmpty()) {
            item(key = "debug_empty_state") {
                MoteCard(background = Color.White) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEFF6FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = CloudFlashCobalt,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = if (logs.isEmpty()) "No LiteRT-LM Diagnostic Traces Yet" else "No logs match '${activeFilter.label}'",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MoteNavy
                        )
                        Text(
                            text = "Every local model init, GPU→CPU fallback, inference failure stack trace, and tensor shape table is captured here automatically.",
                            fontSize = 12.sp,
                            color = SlateMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onRunSelfCheck,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MoteNavy)
                            Spacer(Modifier.width(6.dp))
                            Text("Capture Runtime & Tensor Snapshot", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
                        }
                    }
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { entry ->
                val isTraceExpanded = entry.id in expandedTraceIds
                DiagnosticLogEntryCard(
                    entry = entry,
                    isTraceExpanded = isTraceExpanded,
                    onToggleTrace = {
                        expandedTraceIds = if (isTraceExpanded) {
                            expandedTraceIds - entry.id
                        } else {
                            expandedTraceIds + entry.id
                        }
                    },
                    onCopyTrace = {
                        val payload = buildString {
                            appendLine("[${entry.severity.label}] [${entry.stage.label}] ${entry.modelDisplayName} (${entry.backend})")
                            appendLine(entry.summary)
                            entry.errorClass?.let { appendLine("Exception: $it: ${entry.errorMessage}") }
                            if (entry.tensorShapes.isNotEmpty()) {
                                appendLine("Tensors:")
                                entry.tensorShapes.forEach { t ->
                                    appendLine("  ${t.name} (${t.role}): ${t.formattedShape()} ${t.dtype}")
                                }
                            }
                            entry.stackTrace?.let { appendLine("\n$it") }
                        }
                        copyToClipboard("Error Trace", payload)
                    }
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLogEntryCard(
    entry: LiteRtDiagnosticLogEntry,
    isTraceExpanded: Boolean,
    onToggleTrace: () -> Unit,
    onCopyTrace: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val (badgeBg, badgeFg, borderColor) = when (entry.severity) {
        LiteRtLogSeverity.FATAL, LiteRtLogSeverity.ERROR -> Triple(
            if (tokens.isDark) Color(0xFF450A0A) else Color(0xFFFEE2E2),
            if (tokens.isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
            if (tokens.isDark) Color(0xFF991B1B) else Color(0xFFFECACA)
        )
        LiteRtLogSeverity.WARNING -> Triple(
            if (tokens.isDark) Color(0xFF451A03) else Color(0xFFFEF3C7),
            if (tokens.isDark) Color(0xFFFCD34D) else Color(0xFFB45309),
            if (tokens.isDark) Color(0xFF92400E) else Color(0xFFFDE68A)
        )
        LiteRtLogSeverity.INFO -> Triple(
            if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5),
            if (tokens.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
            MoteBorder
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MoteSurface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag("debug_log_entry_${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header row: Severity + Stage + Timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.severity.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeFg,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(IosCanvas)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.stage.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoteNavy
                    )
                }
            }
            Text(
                text = timeFormatter.format(Date(entry.timestampMs)),
                fontSize = 10.5.sp,
                color = SlateMuted,
                fontFamily = FontFamily.Monospace
            )
        }

        // Model & Backend + Summary
        Text(
            text = "${entry.modelDisplayName} · ${entry.backend}",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = CloudFlashCobalt
        )
        Text(
            text = entry.summary,
            fontSize = 12.5.sp,
            color = MoteNavy,
            lineHeight = 17.sp
        )

        // Exception class & root cause chain
        if (entry.errorClass != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (tokens.isDark) Color(0xFF3B1519) else Color(0xFFFEF2F2))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = entry.errorClass,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (tokens.isDark) Color(0xFFFCA5A5) else Color(0xFF991B1B),
                    fontFamily = FontFamily.Monospace
                )
                entry.errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = if (tokens.isDark) Color(0xFFFECACA) else Color(0xFF7F1D1D),
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // Captured Tensor Shapes Table
        if (entry.tensorShapes.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoteSurfaceSubtle)
                    .border(1.dp, MoteBorder, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GridOn,
                        contentDescription = null,
                        tint = MoteNavy,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "TENSOR SHAPES (${entry.tensorShapes.size})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        letterSpacing = 0.5.sp
                    )
                }
                entry.tensorShapes.forEach { tensor ->
                    TensorShapeRow(tensor)
                }
            }
        }

        // Runtime Metrics Chips
        if (entry.runtimeMetrics.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entry.runtimeMetrics.entries.take(8).forEach { (k, v) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(IosCanvas)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$k=$v",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = SlateMuted
                        )
                    }
                }
            }
        }

        // Expandable Stack Trace Viewer + Copy Trace
        if (!entry.stackTrace.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onToggleTrace,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(
                        imageVector = if (isTraceExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = CloudFlashCobalt,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isTraceExpanded) "Hide Stack Trace" else "Inspect Full Stack Trace",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CloudFlashCobalt
                    )
                }
                TextButton(
                    onClick = onCopyTrace,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 42.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = SlateMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy Trace", fontSize = 11.sp, color = SlateMuted)
                }
            }

            AnimatedVisibility(visible = isTraceExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B1120))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        text = entry.stackTrace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFE2E8F0)
                    )
                }
            }
        }
    }
}

@Composable
private fun TensorShapeRow(tensor: TensorShapeDescriptor) {
    val isNativeError = tensor.role == "NATIVE_ERR"
    val roleColor = when (tensor.role) {
        "NATIVE_ERR" -> Color(0xFFDC2626)
        "INPUT" -> CloudFlashCobalt
        "KV_STATE" -> Color(0xFF7C3AED)
        "OUTPUT" -> LocalNanoEmerald
        else -> SlateMuted
    }
    val tokens = LocalMoteColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isNativeError) {
                    if (tokens.isDark) Color(0xFF450A0A) else Color(0xFFFEE2E2)
                } else {
                    MoteSurface
                }
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = tensor.role,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = roleColor,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = tensor.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MoteNavy,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = tensor.formattedShape(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isNativeError) Color(0xFFB91C1C) else MoteNavy,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = tensor.dtype,
                fontSize = 9.5.sp,
                color = SlateMuted,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DebugStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFCBD5E1),
            letterSpacing = 0.4.sp
        )
    }
}

@Composable
private fun LightMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(IosCanvas)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Bold,
            color = MoteNavy,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = SlateMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
