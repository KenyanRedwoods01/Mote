package com.mote.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.ui.components.GenUiCardRenderer
import com.mote.app.ui.components.IosCanvas
import com.mote.app.ui.components.MoteNavy
import kotlinx.coroutines.launch

/**
 * Transparent Foreground Activity enabling local Gemini Nano 4 inference when a floating
 * onscreen bot is tapped over any third-party Android app.
 */
class HybridInferenceActivity : ComponentActivity() {

    private val router = HybridAiRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)
        val executor = DeviceActionExecutor(this)

        setContent {
            var prompt by remember { mutableStateOf("Set up focus mode: dim screen, DND on, 25m timer") }
            var loading by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<HybridGenUiResult?>(null) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                loading = true
                result = router.generateControlCard(
                    userPrompt = prompt,
                    botSystemPrompt = "Generate concise Android Focus Mode controls.",
                    redactedScreenContext = screenContext,
                    isForeground = true
                )
                loading = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x730B0D1D))
                    .clickable { finish() },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(IosCanvas)
                        .clickable(enabled = false) {}
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Mote Hybrid Sheet", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MoteNavy)

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                loading = true
                                result = router.generateControlCard(
                                    userPrompt = prompt,
                                    botSystemPrompt = "Generate compact native Android UI controls.",
                                    redactedScreenContext = screenContext,
                                    isForeground = true
                                )
                                loading = false
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loading) "Running Hybrid AI..." else "Generate Control Card", color = Color.White)
                    }

                    result?.let { res ->
                        GenUiCardRenderer(result = res) { action ->
                            executor.execute(action)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN_CONTEXT = "extra_screen_context"
    }
}
