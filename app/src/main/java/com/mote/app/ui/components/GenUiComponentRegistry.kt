package com.mote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.GenUiWidgetSpec
import com.mote.app.ai.MoteAction

typealias GenUiWidgetComposable = @Composable (
    spec: GenUiWidgetSpec,
    accent: Color,
    onExecuteAction: (MoteAction) -> Unit
) -> Unit

data class GenUiComponentDescriptor(
    val typeKey: String,
    val displayName: String,
    val category: String,
    val renderer: GenUiWidgetComposable
)

/**
 * Extensible runtime registry mapping AI-emitted Generative UI widget keys
 * (e.g., bar_chart, calendar, toggle, slider, checklist, quiz_card) to native Compose renderers.
 */
object GenUiComponentRegistry {

    private val entries = linkedMapOf<String, GenUiComponentDescriptor>()

    init {
        register("metric_grid", "Metric Grid", "Data") { spec, accent, _ ->
            GenUiMetricGrid(spec, accent)
        }
        register("bar_chart", "Bar Chart", "Charts") { spec, accent, _ ->
            GenUiBarChart(spec, accent)
        }
        register("calendar", "Calendar Schedule", "Schedule") { spec, accent, onAction ->
            GenUiCalendarWidget(spec, accent, onAction)
        }
        register("checklist", "Interactive Checklist", "Tasks") { spec, accent, _ ->
            GenUiInteractiveChecklist(spec, accent)
        }
        register("comparison_table", "Comparison Table", "Analysis") { spec, accent, _ ->
            GenUiComparisonTable(spec, accent)
        }
        register("timeline", "Timeline Stepper", "Schedule") { spec, accent, _ ->
            GenUiTimeline(spec, accent)
        }
        register("quiz_card", "Interactive Quiz", "Learning") { spec, accent, _ ->
            GenUiInteractiveQuiz(spec, accent)
        }
        register("slider", "Live Slider", "Controls") { spec, accent, onAction ->
            GenUiSlider(spec, accent, onAction)
        }
        register("toggle", "Spring Toggle", "Controls") { spec, _, onAction ->
            GenUiToggle(spec, onAction)
        }
        register("action_chip", "Device Action", "Controls") { spec, accent, onAction ->
            GenUiActionButton(spec, accent, onAction)
        }
        register("confirm_button", "Confirm Action", "Controls") { spec, accent, onAction ->
            GenUiActionButton(spec, accent, onAction)
        }
        register("reply_pills", "Reply Pills", "Messaging") { spec, _, onAction ->
            GenUiReplyPills(spec, onAction)
        }
        register("info_card", "Info Callout", "Summary") { spec, accent, onAction ->
            GenUiInfoCard(spec, accent, onAction)
        }
        register("alarm_manager", "Alarm Manager", "Clock Tool") { spec, accent, onAction ->
            GenUiInfoCard(spec, accent, onAction)
        }
        register("countdown_timer", "Live Countdown", "Clock Tool") { spec, accent, onAction ->
            GenUiActionButton(spec, accent, onAction)
        }
        register("stopwatch", "Live Stopwatch", "Clock Tool") { spec, accent, onAction ->
            GenUiInfoCard(spec, accent, onAction)
        }
    }

    fun register(
        typeKey: String,
        displayName: String,
        category: String,
        renderer: GenUiWidgetComposable
    ) {
        entries[typeKey] = GenUiComponentDescriptor(typeKey, displayName, category, renderer)
    }

    fun resolve(typeKey: String): GenUiComponentDescriptor? = entries[typeKey]

    fun registeredDescriptors(): List<GenUiComponentDescriptor> = entries.values.toList()
}

@Composable
internal fun GenUiCalendarWidget(
    w: GenUiWidgetSpec,
    accent: Color,
    onExecuteAction: (MoteAction) -> Unit
) {
    val events = w.calendarEvents
    if (events.isEmpty()) return
    var selectedIdx by remember(w.id) { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MoteSurfaceSubtle)
            .border(1.dp, MoteBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
            .testTag("gen_ui_calendar_${w.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = w.label.ifBlank { "Schedule" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MoteNavy
            )
        }

        events.forEachIndexed { idx, ev ->
            val picked = idx == selectedIdx
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (picked) MoteSurface else IosCanvas)
                    .border(
                        width = if (picked) 1.25.dp else 0.75.dp,
                        color = if (picked) accent else MoteBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable {
                        selectedIdx = idx
                        onExecuteAction(MoteAction.CopyText("${ev.dayLabel} ${ev.timeRange}: ${ev.title}".trim()))
                    }
                    .padding(10.dp)
                    .testTag("gen_ui_calendar_item_${w.id}_$idx"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (picked) accent else MoteBrandNavy)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ev.dayLabel,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ev.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoteNavy
                    )
                    if (ev.timeRange.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = SlateMuted, modifier = Modifier.size(12.dp))
                            Text(ev.timeRange, fontSize = 11.sp, color = SlateMuted)
                        }
                    }
                    if (ev.location.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.Place, contentDescription = null, tint = SlateMuted, modifier = Modifier.size(12.dp))
                            Text(ev.location, fontSize = 11.sp, color = SlateMuted)
                        }
                    }
                }
            }
        }
    }
}
