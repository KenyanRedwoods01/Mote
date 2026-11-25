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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.control.DeviceActionExecutor
import com.mote.app.security.configureAppCheck
import com.mote.app.ui.components.GenUiCardRenderer
import com.mote.app.ui.components.IosCanvas
import com.mote.app.ui.components.MoteCard
import com.mote.app.ui.components.MoteNavy
import kotlinx.coroutines.launch

/**
 * Transparent Foreground Activity enabling Generative UI cards when a floating
 * onscreen bot is tapped over any third-party Android app.
 */
class HybridInferenceActivity : ComponentActivity() {

    private val router by lazy { HybridAiRouter() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)

        val screenContext = intent.getStringExtra(EXTRA_SCREEN_CONTEXT)
        val executor = DeviceActionExecutor(this)

        setContent {
            var prompt by remember { mutableStateOf("Set up focus mode: dim screen, DND on, 25m timer") }
            var loading by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf<HybridGenUiResult?>(null) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()

            suspend fun runInference(inputPrompt: String, sysPrompt: String) {
                loading = true
                errorMessage = null
                router.generateControlCard(
                    userPrompt = inputPrompt,
                    botSystemPrompt = sysPrompt,
                    redactedScreenContext = screenContext,
                    isForeground = true
                ).onSuccess { res ->
                    result = res
                }.onFailure { err ->
                    errorMessage = err.localizedMessage ?: "Failed to generate control card."
                }
                loading = false
            }

            LaunchedEffect(Unit) {
                runInference(prompt, "Generate concise Android Focus Mode controls.")
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
                        .navigationBarsPadding()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Mote Hybrid Sheet", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MoteNavy)

                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hybrid_prompt_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                runInference(prompt, "Generate compact native Android UI controls.")
                            }
                        },
                        enabled = !loading,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MoteNavy),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("generate_control_card_button")
                    ) {
                        Text(if (loading) "Running Gemini AI..." else "Generate Control Card", color = Color.White)
                    }

                    errorMessage?.let { msg ->
                        MoteCard(background = Color(0xFFFEF2F2)) {
                            Text(
                                text = msg,
                                color = Color(0xFFB91C1C),
                                fontSize = 12.5.sp
                            )
                        }
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
