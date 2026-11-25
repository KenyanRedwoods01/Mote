package com.mote.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.EngineUsed
import com.mote.app.ai.GenUiWidgetSpec
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.MoteAction
import com.mote.app.ui.theme.LocalMoteColors
import java.util.Locale

private fun accentColorFor(name: String): Color = when (name.lowercase()) {
    "emerald" -> LocalNanoEmerald
    "amber" -> Color(0xFFD97706)
    "violet" -> Color(0xFF7C3AED)
    "slate" -> Color(0xFF334155)
    else -> CloudFlashCobalt
}

/**
 * Rich Native Jetpack Compose Generative UI Renderer.
 * Dynamically materializes interactive dashboards, bar charts, checklists, comparison tables,
 * timelines, interactive quizzes, sliders, switches, and allowlisted Android device controls
 * directly from structured Gemini JSON output.
 */
@Composable
fun GenUiCardRenderer(
    result: HybridGenUiResult,
    modifier: Modifier = Modifier,
    onExecuteAction: (MoteAction) -> Unit
) {
    val tokens = LocalMoteColors.current
    val accent = accentColorFor(result.spec.themeAccent)
    val badgeBg = when (result.engineUsed) {
        EngineUsed.LOCAL_NANO -> if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
        EngineUsed.CLOUD_FLASH -> if (tokens.isDark) Color(0xFF1E3A8A) else Color(0xFFEFF6FF)
        EngineUsed.OFFLINE_FALLBACK -> MoteSurfaceSubtle
    }
    val badgeFg = when (result.engineUsed) {
        EngineUsed.LOCAL_NANO -> if (tokens.isDark) Color(0xFF6EE7B7) else Color(0xFF065F46)
        EngineUsed.CLOUD_FLASH -> if (tokens.isDark) Color(0xFF93C5FD) else Color(0xFF1E3A8A)
        EngineUsed.OFFLINE_FALLBACK -> SlateMuted
    }
    val badgeLabel = "${result.modelName} · ${result.latencyMs}ms"

    MoteCard(
        modifier = modifier
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .testTag("gen_ui_result_card")
    ) {
        // Card Header with Lobe Model Icon & Latency Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LobeModelAvatar(modelId = result.modelName, size = 34.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.spec.title,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (result.spec.subtitle.isNotBlank()) {
                        Text(
                            text = result.spec.subtitle,
                            fontSize = 11.5.sp,
                            color = SlateMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeBg)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(badgeLabel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = badgeFg)
            }
        }

        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            result.spec.widgets.forEach { w ->
                val descriptor = GenUiComponentRegistry.resolve(w.type)
                if (descriptor != null) {
                    descriptor.renderer(w, accent, onExecuteAction)
                } else {
                    GenUiInfoCard(w, accent, onExecuteAction)
                }
            }
        }
    }
}

@Composable
internal fun GenUiMetricGrid(w: GenUiWidgetSpec, accent: Color) {
    if (w.metrics.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (w.label.isNotBlank()) {
            Text(w.label.uppercase(Locale.US), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
        }
        w.metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowMetrics.forEach { metric ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MoteSurfaceSubtle)
                            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Text(metric.label, fontSize = 11.sp, color = SlateMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(metric.value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                        if (metric.delta.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(metric.delta, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = accent)
                        }
                    }
                }
                if (rowMetrics.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun GenUiBarChart(w: GenUiWidgetSpec, accent: Color) {
    if (w.chartBars.isEmpty()) return
    val maxVal = (w.chartBars.maxOfOrNull { it.value } ?: 1f).coerceAtLeast(1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(w.label, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        w.chartBars.forEach { bar ->
            val targetFraction = (bar.value / maxVal).coerceIn(0.05f, 1f)
            val animatedFraction by animateFloatAsState(
                targetValue = targetFraction,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
                label = "barFraction"
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(bar.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MoteNavy)
                    val formatted = if (bar.value % 1f == 0f) bar.value.toInt().toString() else String.format(Locale.US, "%.1f", bar.value)
                    Text("$formatted${bar.unit}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = accent)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE2E8F0))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedFraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(accent, LocalNanoEmerald)))
                    )
                }
            }
        }
    }
}

@Composable
internal fun GenUiInteractiveChecklist(w: GenUiWidgetSpec, accent: Color) {
    if (w.options.isEmpty()) return
    val checkedIndices = remember(w.id) { mutableStateMapOf<Int, Boolean>() }
    val doneCount = checkedIndices.values.count { it }
    val progress = doneCount.toFloat() / w.options.size.coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MoteNavy, modifier = Modifier.weight(1f))
            Text("$doneCount/${w.options.size}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        LinearProgressIndicator(
            progress = { progress },
            color = accent,
            trackColor = Color(0xFFE2E8F0),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
        )
        w.options.forEachIndexed { idx, itemText ->
            val checked = checkedIndices[idx] == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { checkedIndices[idx] = !checked }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .testTag("gen_ui_check_${w.id}_$idx"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (checked) Icons.Rounded.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (checked) "Completed" else "Mark complete",
                    tint = if (checked) LocalNanoEmerald else SlateMuted,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = itemText,
                    fontSize = 13.sp,
                    color = if (checked) SlateMuted else MoteNavy,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun GenUiComparisonTable(w: GenUiWidgetSpec, accent: Color) {
    if (w.comparisonRows.isEmpty()) return
    val leftHeader = w.comparisonHeaders.getOrNull(0) ?: "Option A"
    val rightHeader = w.comparisonHeaders.getOrNull(1) ?: "Option B"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MoteBrandNavy)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("Feature", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), modifier = Modifier.weight(1f))
            Text(leftHeader, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text(rightHeader, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF93C5FD), modifier = Modifier.weight(1f))
        }
        w.comparisonRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(row.feature, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy, modifier = Modifier.weight(1f).padding(end = 4.dp))
                Text(row.leftValue, fontSize = 11.5.sp, color = SlateMuted, modifier = Modifier.weight(1f).padding(end = 4.dp))
                Text(row.rightValue, fontSize = 11.5.sp, color = accent, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = MoteBorder)
        }
    }
}

@Composable
internal fun GenUiTimeline(w: GenUiWidgetSpec, accent: Color) {
    if (w.timelineSteps.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
        w.timelineSteps.forEachIndexed { idx, step ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (idx == 0) LocalNanoEmerald else accent)
                    )
                    if (idx < w.timelineSteps.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(26.dp)
                                .background(Color(0xFFCBD5E1))
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(step.time, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text(step.title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                    }
                    if (step.detail.isNotBlank()) {
                        Text(step.detail, fontSize = 11.5.sp, color = SlateMuted, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun GenUiInteractiveQuiz(w: GenUiWidgetSpec, accent: Color) {
    if (w.options.isEmpty()) return
    var selectedIdx by remember(w.id) { mutableStateOf<Int?>(null) }
    val correctIdx = (w.correctOptionIndex ?: 0).coerceIn(0, w.options.lastIndex)

    val tokens = LocalMoteColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("INTERACTIVE CHECK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
        Text(w.label, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)

        w.options.forEachIndexed { idx, opt ->
            val isPicked = selectedIdx == idx
            val isCorrect = idx == correctIdx
            val bg = when {
                selectedIdx == null -> MoteSurface
                isCorrect -> if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
                isPicked -> if (tokens.isDark) Color(0xFF3B1519) else Color(0xFFFEF2F2)
                else -> MoteSurface
            }
            val borderColor = when {
                selectedIdx == null -> MoteBorder
                isCorrect -> LocalNanoEmerald
                isPicked -> Color(0xFFDC2626)
                else -> MoteBorder
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                    .clickable { selectedIdx = idx }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("gen_ui_quiz_${w.id}_$idx"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(opt, fontSize = 12.5.sp, color = MoteNavy, modifier = Modifier.weight(1f))
                if (selectedIdx != null && (isCorrect || isPicked)) {
                    Icon(
                        imageVector = if (isCorrect) Icons.Outlined.CheckCircle else Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = if (isCorrect) LocalNanoEmerald else Color(0xFFDC2626),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (selectedIdx != null && w.explanation.isNotBlank()) {
            Text(
                text = w.explanation,
                fontSize = 12.sp,
                color = MoteNavy,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (tokens.isDark) Color(0xFF172554) else Color(0xFFEFF6FF))
                    .padding(9.dp)
            )
        }
    }
}

@Composable
internal fun GenUiSlider(w: GenUiWidgetSpec, accent: Color, onExecuteAction: (MoteAction) -> Unit) {
    var sliderVal by remember(w.id) { mutableFloatStateOf((w.intValue ?: 50).coerceIn(10, 100).toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MoteNavy)
            Text("${sliderVal.toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)
        }
        Slider(
            value = sliderVal,
            onValueChange = {
                sliderVal = it
                if (w.actionType == "SET_BRIGHTNESS") {
                    onExecuteAction(MoteAction.SetBrightness(it.toInt()))
                }
            },
            valueRange = 10f..100f,
            colors = SliderDefaults.colors(thumbColor = MoteNavy, activeTrackColor = accent),
            modifier = Modifier.testTag("gen_ui_slider_${w.id}")
        )
    }
}

@Composable
internal fun GenUiToggle(w: GenUiWidgetSpec, onExecuteAction: (MoteAction) -> Unit) {
    var checked by remember(w.id) { mutableStateOf(w.boolValue ?: false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MoteNavy, modifier = Modifier.weight(1f))
        IosSpringSwitch(
            checked = checked,
            onCheckedChange = {
                checked = it
                if (w.actionType == "TOGGLE_DND") {
                    onExecuteAction(MoteAction.ToggleDnd(it))
                }
            },
            modifier = Modifier.testTag("gen_ui_toggle_${w.id}")
        )
    }
}

@Composable
internal fun GenUiActionButton(w: GenUiWidgetSpec, accent: Color, onExecuteAction: (MoteAction) -> Unit) {
    Button(
        onClick = {
            when (w.actionType) {
                "START_TIMER" -> onExecuteAction(MoteAction.StartTimer(w.intValue ?: 25, w.label))
                "COPY_TEXT" -> onExecuteAction(MoteAction.CopyText(w.label))
                "TOGGLE_DND" -> onExecuteAction(MoteAction.ToggleDnd(w.boolValue ?: true))
                "SET_BRIGHTNESS" -> onExecuteAction(MoteAction.SetBrightness(w.intValue ?: 30))
                "SEND_MESSAGE" -> onExecuteAction(MoteAction.ConfirmSendMessage(w.label))
                else -> onExecuteAction(MoteAction.CopyText(w.label))
            }
        },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (w.requiresConfirm) accent else LocalMoteColors.current.primaryActionBg),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("gen_ui_action_${w.id}")
    ) {
        Text(w.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun GenUiReplyPills(w: GenUiWidgetSpec, onExecuteAction: (MoteAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (w.label.isNotBlank()) {
            Text(w.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SlateMuted)
        }
        w.options.forEachIndexed { idx, reply ->
            OutlinedButton(
                onClick = { onExecuteAction(MoteAction.PasteReply(reply)) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("gen_ui_reply_${w.id}_$idx")
            ) {
                Text(reply, fontSize = 12.5.sp, color = MoteNavy)
            }
        }
    }
}

@Composable
internal fun GenUiInfoCard(w: GenUiWidgetSpec, accent: Color, onExecuteAction: (MoteAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(IosCanvas)
            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(w.label, fontSize = 12.5.sp, color = MoteNavy, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { onExecuteAction(MoteAction.CopyText(w.label)) },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy info",
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
