package com.mote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.EngineUsed
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.MoteAction

@Composable
fun GenUiCardRenderer(
    result: HybridGenUiResult,
    onExecuteAction: (MoteAction) -> Unit
) {
    val isNano = result.engineUsed == EngineUsed.LOCAL_NANO
    val badgeBg = if (isNano) Color(0xFFECFDF5) else Color(0xFFEFF6FF)
    val badgeFg = if (isNano) Color(0xFF065F46) else Color(0xFF1E3A8A)
    val badgeLabel = when (result.engineUsed) {
        EngineUsed.LOCAL_NANO -> "Local Nano 4 · ${result.latencyMs}ms"
        EngineUsed.CLOUD_FLASH -> "Cloud Flash · ${result.latencyMs}ms"
        EngineUsed.OFFLINE_FALLBACK -> "Offline Safe Controls"
    }

    MoteCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(result.spec.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(badgeLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = badgeFg)
            }
        }

        Spacer(Modifier.height(12.dp))

        result.spec.widgets.forEach { w ->
            when (w.type) {
                "slider" -> {
                    var sliderVal by remember(w.id) { mutableFloatStateOf((w.intValue ?: 50).toFloat()) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MoteNavy)
                        Text("${sliderVal.toInt()}%", fontSize = 13.sp, color = Color(0xFF475569))
                    }
                    Slider(
                        value = sliderVal,
                        onValueChange = {
                            sliderVal = it
                            if (w.actionType == "SET_BRIGHTNESS") {
                                onExecuteAction(MoteAction.SetBrightness(it.toInt()))
                            }
                        },
                        valueRange = 10f..100f
                    )
                }
                "toggle" -> {
                    var checked by remember(w.id) { mutableStateOf(w.boolValue ?: false) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MoteNavy)
                        IosSpringSwitch(
                            checked = checked,
                            onCheckedChange = {
                                checked = it
                                if (w.actionType == "TOGGLE_DND") {
                                    onExecuteAction(MoteAction.ToggleDnd(it))
                                }
                            }
                        )
                    }
                }
                "action_chip" -> {
                    Button(
                        onClick = {
                            if (w.actionType == "START_TIMER") {
                                onExecuteAction(MoteAction.StartTimer(w.intValue ?: 25, w.label))
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        Text(w.label, color = Color.White, fontSize = 13.sp)
                    }
                }
                "reply_pills" -> {
                    w.options.forEach { reply ->
                        OutlinedButton(
                            onClick = { onExecuteAction(MoteAction.PasteReply(reply)) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(reply, fontSize = 12.5.sp, color = MoteNavy)
                        }
                    }
                }
            }
        }
    }
}
