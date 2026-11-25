package com.mote.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.LlmConfig
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelEntry
import com.mote.app.ui.screens.formatModelBytes
import com.mote.app.ui.theme.LocalMoteColors

/**
 * Real-time Model & Runtime Switcher sheet opened directly from Chat Hub.
 * Allows instant 1-tap switching between:
 * 1. Plugged-in Cloud Gemini API models (Firebase AI Logic: Gemini 3.8 Flash, 3.5 Flash-Lite, 4 Argon)
 * 2. Downloaded On-Device LiteRT-LM phone models (auto-loads into RAM and routes chat offline)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatModelSwitcherBottomSheet(
    llmConfig: LlmConfig,
    localModels: List<LocalModelEntry>,
    loadedLocalModel: LoadedLocalModelState?,
    loadingLocalModelId: String?,
    preferLoadedLocalChat: Boolean,
    onSelectCloudModel: (String) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onOpenModelHub: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalMoteColors.current
    val downloadedModels = localModels.filter { it.status == LocalModelDownloadStatus.DOWNLOADED }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MoteSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp)
                .testTag("chat_model_switcher_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Switch Active AI Model",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy
                    )
                    Text(
                        text = "Real-time Cloud Gemini API or Downloaded Offline Phone Models",
                        fontSize = 11.5.sp,
                        color = SlateMuted
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close model switcher", tint = SlateMuted)
                }
            }

            // Section 1: Plugged-in Cloud Gemini API (Firebase AI Logic)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.CloudDone, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(15.dp))
                Text(
                    text = "CLOUD GEMINI API · PLUGGED IN (REAL-TIME)",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = CloudFlashCobalt
                )
            }

            HybridAiRouter.supportedModels.forEach { cloudModel ->
                val isSelected = !preferLoadedLocalChat && llmConfig.modelName == cloudModel.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isSelected) {
                                if (tokens.isDark) Color(0xFF172554) else Color(0xFFEFF6FF)
                            } else {
                                IosCanvas
                            }
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) CloudFlashCobalt else MoteBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            onSelectCloudModel(cloudModel.id)
                            onDismiss()
                        }
                        .padding(12.dp)
                        .testTag("switcher_cloud_${cloudModel.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LobeModelAvatar(modelId = cloudModel.id, size = 38.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = cloudModel.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MoteNavy
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MoteSurface)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = cloudModel.contextLabel,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CloudFlashCobalt
                                )
                            }
                        }
                        Text(
                            text = cloudModel.badge,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalNanoEmerald
                        )
                        Text(
                            text = cloudModel.description,
                            fontSize = 11.sp,
                            color = SlateMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Active Cloud Model" else "Select Cloud Model",
                        tint = if (isSelected) CloudFlashCobalt else SlateMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider(color = MoteBorder)

            // Section 2: Downloaded On-Device Models (LiteRT-LM)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.Memory, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(15.dp))
                    Text(
                        text = "DOWNLOADED ON-DEVICE MODELS (${downloadedModels.size})",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = LocalNanoEmerald
                    )
                }
            }

            if (downloadedModels.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IosCanvas)
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "No .litertlm phone models downloaded yet",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MoteNavy
                    )
                    Text(
                        text = "Download FunctionGemma 270M or Qwen 3 0.6B in Model Hub to run private offline chat with tool calls.",
                        fontSize = 11.5.sp,
                        color = SlateMuted
                    )
                }
            } else {
                downloadedModels.forEach { entry ->
                    val isLoaded = loadedLocalModel?.modelId == entry.id
                    val isActiveForChat = preferLoadedLocalChat && isLoaded
                    val isLoading = loadingLocalModelId == entry.id

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isActiveForChat) {
                                    if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5)
                                } else {
                                    IosCanvas
                                }
                            )
                            .border(
                                width = if (isActiveForChat) 1.5.dp else 1.dp,
                                color = if (isActiveForChat) LocalNanoEmerald else MoteBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(enabled = !isLoading) {
                                onSelectLocalModel(entry.id)
                                onDismiss()
                            }
                            .padding(12.dp)
                            .testTag("switcher_local_${entry.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LobeModelAvatar(brand = entry.providerBrand, size = 38.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MoteNavy,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    isLoading -> "Loading weights into RAM..."
                                    isActiveForChat -> "Active Offline in RAM (${loadedLocalModel?.backend?.label ?: "CPU"})"
                                    isLoaded -> "Loaded in RAM · Tap to route chat offline"
                                    else -> "${formatModelBytes(entry.downloadedBytes)} on disk · Tap to Load & Chat"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isActiveForChat) LocalNanoEmerald else CloudFlashCobalt
                            )
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = LocalNanoEmerald
                            )
                        } else {
                            Icon(
                                imageVector = if (isActiveForChat) Icons.Outlined.CheckCircle else Icons.Outlined.PlayCircle,
                                contentDescription = if (isActiveForChat) "Active Offline Model" else "Load and Chat",
                                tint = if (isActiveForChat) LocalNanoEmerald else MoteNavy,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    onDismiss()
                    onOpenModelHub()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("switcher_open_model_hub_button")
            ) {
                Icon(Icons.Outlined.DownloadForOffline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MoteNavy)
                Spacer(Modifier.width(6.dp))
                Text("Download & Manage Phone Models", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MoteNavy)
            }
        }
    }
}
