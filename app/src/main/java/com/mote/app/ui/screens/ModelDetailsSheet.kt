package com.mote.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mote.app.ai.LocalHardwareBackend
import com.mote.app.data.BenchmarkPromptPreset
import com.mote.app.data.DeviceHardwareSnapshot
import com.mote.app.data.LiveBenchmarkProgress
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelEntry
import com.mote.app.data.db.ModelBenchmarkEntity
import com.mote.app.ui.components.*
import com.mote.app.ui.theme.LocalMoteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailsSheet(
    entry: LocalModelEntry,
    hardware: DeviceHardwareSnapshot,
    isLoaded: Boolean,
    isLoading: Boolean,
    localBackendChoice: LocalHardwareBackend,
    isBenchmarking: Boolean,
    liveBenchmark: LiveBenchmarkProgress?,
    modelBenchmarks: List<ModelBenchmarkEntity>,
    onSelectBackend: (LocalHardwareBackend) -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRunBenchmark: (BenchmarkPromptPreset) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf(BenchmarkPromptPreset.QUICK_QA) }
    val latestBenchmark = modelBenchmarks.firstOrNull()
    val fitsRam = hardware.totalRamMb == 0L || hardware.totalRamMb >= entry.minRamMb
    val isThisModelBenchmarking = isBenchmarking && liveBenchmark?.modelId == entry.id

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = IosCanvas,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp)
                .testTag("model_details_sheet_${entry.id}"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Model Identity & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LobeModelAvatar(brand = entry.providerBrand, size = 48.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MoteNavy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${entry.family} · ${entry.parameterCount} · ${entry.quantLabel} · ${formatApproxSize(entry.approxSizeMb)}",
                        fontSize = 12.sp,
                        color = SlateMuted
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp).testTag("close_model_details_button")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close details", tint = SlateMuted)
                }
            }

            // Specs & Capabilities Card
            MoteCard {
                Text(entry.summary, fontSize = 12.5.sp, color = SlateMuted, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailMetricTile("DOWNLOAD SIZE", formatApproxSize(entry.approxSizeMb), Modifier.weight(1f))
                    DetailMetricTile(
                        label = "RAM FIT",
                        value = "${entry.minRamMb / 1024}GB · ${if (fitsRam) "Fits" else "Tight"}",
                        modifier = Modifier.weight(1f),
                        accent = if (fitsRam) LocalNanoEmerald else Color(0xFFD97706)
                    )
                    DetailMetricTile("CONTEXT", "${entry.contextLengthTokens} tok", Modifier.weight(1f))
                }

                if (entry.capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        entry.capabilities.forEach { cap ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFFEFF6FF))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(cap.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CloudFlashCobalt)
                            }
                        }
                    }
                }

                if (entry.status == LocalModelDownloadStatus.DOWNLOADED && entry.localFilePath != null) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MoteBorder)
                    Spacer(Modifier.height(8.dp))
                    val shaStr = entry.sha256Prefix?.let { " · SHA-256 $it" }.orEmpty()
                    Text(
                        text = "Room Verified File: ${entry.fileName} (${formatModelBytes(entry.downloadedBytes)})$shaStr",
                        fontSize = 10.5.sp,
                        color = LocalNanoEmerald,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Device Hardware Profile Card
            MoteCard(background = Color(0xFFF8FAFC)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.DeveloperBoard, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(18.dp))
                    Text("YOUR DEVICE HARDWARE PROFILE", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${hardware.deviceModel} · ${hardware.socLabel} (${hardware.primaryAbi})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MoteNavy
                )
                Text(
                    text = "${hardware.cpuCores} CPU cores · ${hardware.availableRamMb} MB free of ${hardware.totalRamMb} MB RAM · ${hardware.freeStorageMb / 1024} GB storage free",
                    fontSize = 11.sp,
                    color = SlateMuted
                )
            }

            // Hardware Benchmarking Suite Card
            MoteCard(
                modifier = Modifier
                    .border(1.dp, CloudFlashCobalt.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .testTag("model_benchmark_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Speed, contentDescription = null, tint = CloudFlashCobalt, modifier = Modifier.size(19.dp))
                        Text("TOKENS/SEC HARDWARE BENCHMARK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LocalHardwareBackend.entries.forEach { backend ->
                            FilterChip(
                                selected = localBackendChoice == backend,
                                onClick = { onSelectBackend(backend) },
                                label = { Text(backend.label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold) },
                                modifier = Modifier.testTag("details_backend_${backend.name.lowercase()}")
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Select a real inference workload to measure live tokens/sec, TTFT, and thermal/battery draw directly on your device:",
                    fontSize = 11.5.sp,
                    color = SlateMuted,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BenchmarkPromptPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset,
                            onClick = { selectedPreset = preset },
                            enabled = !isBenchmarking,
                            label = { Text(preset.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            modifier = Modifier.testTag("benchmark_preset_${preset.name.lowercase()}")
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (entry.status == LocalModelDownloadStatus.DOWNLOADED) {
                    Button(
                        onClick = { onRunBenchmark(selectedPreset) },
                        enabled = !isBenchmarking && !isLoading,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LocalMoteColors.current.primaryActionBg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("run_model_benchmark_button")
                    ) {
                        if (isThisModelBenchmarking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = LocalNanoEmerald, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Benchmarking ${selectedPreset.label} on ${localBackendChoice.label}...", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                        } else {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = LocalNanoEmerald, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Run Benchmark (${selectedPreset.label} · ${localBackendChoice.label})",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("download_to_benchmark_button")
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, tint = MoteNavy, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Download First to Benchmark (${formatApproxSize(entry.approxSizeMb)})",
                            color = MoteNavy,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp
                        )
                    }
                }

                // Live streaming benchmark readout while active
                if (isThisModelBenchmarking && liveBenchmark != null) {
                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MoteBrandNavy)
                            .padding(12.dp)
                            .testTag("live_benchmark_progress_box"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(Locale.US, "LIVE: %.1f tok/s", liveBenchmark.liveTokensPerSec),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = LocalNanoEmerald
                            )
                            Text(
                                text = "${liveBenchmark.approxTokens} tok · ${liveBenchmark.elapsedMs}ms",
                                fontSize = 11.5.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                        if (liveBenchmark.partialPreview.isNotBlank()) {
                            Text(
                                text = liveBenchmark.partialPreview,
                                fontSize = 11.5.sp,
                                color = Color.White.copy(alpha = 0.88f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Latest measured metrics persisted in Room
                if (latestBenchmark != null) {
                    val tokens = LocalMoteColors.current
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MoteBorder)
                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "MEASURED ON THIS DEVICE (${latestBenchmark.promptProfile} · ${latestBenchmark.backendUsed})",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = LocalNanoEmerald
                    )
                    Spacer(Modifier.height(8.dp))

                    // Hero Tokens/sec banner + key telemetry
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (tokens.isDark) Color(0xFF064E3B) else Color(0xFFECFDF5))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("latest_benchmark_hero_metrics"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "DECODE THROUGHPUT",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (tokens.isDark) Color(0xFF6EE7B7) else Color(0xFF065F46)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f tok/s", latestBenchmark.tokensPerSec),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (tokens.isDark) Color(0xFFA7F3D0) else Color(0xFF047857)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("TTFT ${latestBenchmark.ttftMs} ms", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MoteNavy)
                            Text("Total ${latestBenchmark.totalLatencyMs} ms · ${latestBenchmark.generatedTokens} tok", fontSize = 11.sp, color = SlateMuted)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailMetricTile(
                            label = "PEAK TEMP",
                            value = if (latestBenchmark.peakTempC > 0f) String.format(Locale.US, "%.1f°C", latestBenchmark.peakTempC) else "N/A",
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetricTile(
                            label = "PEAK DRAW",
                            value = if (latestBenchmark.peakDrawMa > 0) "${latestBenchmark.peakDrawMa} mA" else "Charging/N/A",
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetricTile(
                            label = "HARDWARE",
                            value = "${latestBenchmark.cpuCores}c ${latestBenchmark.backendUsed}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (!isThisModelBenchmarking) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "No benchmark recorded on this device yet. Run a test above to measure real tokens/sec performance.",
                        fontSize = 11.5.sp,
                        color = SlateMuted
                    )
                }

                // Recent Benchmark History (compare runs)
                if (modelBenchmarks.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("RECENT BENCHMARK RUNS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
                    Spacer(Modifier.height(6.dp))
                    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }
                    modelBenchmarks.take(5).forEach { run ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${run.promptProfile} · ${run.backendUsed}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MoteNavy
                                )
                                Text(
                                    text = "${fmt.format(Date(run.benchmarkedAtEpochMs))} · TTFT ${run.ttftMs}ms · ${String.format(Locale.US, "%.1f°C", run.peakTempC)}",
                                    fontSize = 10.5.sp,
                                    color = SlateMuted
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MoteSurfaceSubtle)
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.1f tok/s", run.tokensPerSec),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MoteNavy
                                )
                            }
                        }
                    }
                }
            }

            // Load / Unload Quick Action Footer
            if (entry.status == LocalModelDownloadStatus.DOWNLOADED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = if (isLoaded) onUnload else onLoad,
                        enabled = !isLoading && !isBenchmarking,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp)
                    ) {
                        Text(
                            text = if (isLoaded) "Unload from RAM" else "Load into RAM",
                            fontWeight = FontWeight.SemiBold,
                            color = if (isLoaded) Color(0xFFDC2626) else MoteNavy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MoteNavy
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MoteSurfaceSubtle)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SlateMuted)
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
