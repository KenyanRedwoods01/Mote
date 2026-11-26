package com.mote.app.control

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

data class NetworkStatusSnapshot(
    val isConnected: Boolean = false,
    val transportLabel: String = "Offline",
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val isVpn: Boolean = false,
    val isMetered: Boolean = false,
    val isAirplaneMode: Boolean = false,
    val wifiSsid: String? = null,
    val signalDbm: Int? = null,
    val signalBars: Int = 0, // 0..4
    val linkSpeedMbps: Int? = null,
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    val localIpv4: String? = null,
    val lastLatencyMs: Long? = null,
    val lastCheckedMs: Long = System.currentTimeMillis()
)

data class NetworkActionOutcome(
    val success: Boolean,
    val summary: String,
    val detail: String,
    val snapshot: NetworkStatusSnapshot
)

/**
 * Real Android Wi-Fi & Network Tools backed by [ConnectivityManager], [NetworkCapabilities],
 * [WifiManager], and Play-compliant Android [Settings.Panel] intents (zero restricted permissions).
 */
class NetworkToolsManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(readNetworkSnapshot())
    val status: StateFlow<NetworkStatusSnapshot> = _status.asStateFlow()

    fun refreshStatus(): NetworkStatusSnapshot {
        val snap = readNetworkSnapshot(lastLatencyMs = _status.value.lastLatencyMs)
        _status.value = snap
        return snap
    }

    fun readNetworkSnapshot(lastLatencyMs: Long? = null): NetworkStatusSnapshot {
        val airplaneMode = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        }.getOrDefault(false)

        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { connectivityManager.getLinkProperties(it) }

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isMetered = connectivityManager.isActiveNetworkMetered

        val transportLabel = when {
            airplaneMode && !hasInternet -> "Airplane Mode"
            !hasInternet -> "Offline"
            isWifi && isVpn -> "Wi-Fi + VPN"
            isCellular && isVpn -> "Cellular + VPN"
            isWifi -> "Wi-Fi"
            isCellular -> "Mobile Data"
            isEthernet -> "Ethernet"
            isVpn -> "VPN"
            else -> "Connected"
        }

        val localIpv4 = linkProps?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        var ssid: String? = null
        var rssi: Int? = null
        var bars = 0
        var linkSpeed: Int? = null

        if (isWifi) {
            @Suppress("DEPRECATION")
            val wifiInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
            if (wifiInfo != null) {
                val rawSsid = wifiInfo.ssid?.trim('"')
                if (!rawSsid.isNullOrBlank() && !rawSsid.equals("<unknown ssid>", ignoreCase = true)) {
                    ssid = rawSsid
                }
                if (wifiInfo.rssi in -120..-1) {
                    rssi = wifiInfo.rssi
                    bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { wifiManager?.calculateSignalLevel(wifiInfo.rssi) ?: 3 }.getOrDefault(3)
                            .coerceIn(0, 4)
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.calculateSignalLevel(wifiInfo.rssi, 5).coerceIn(0, 4)
                    }
                }
                if (wifiInfo.linkSpeed > 0) {
                    linkSpeed = wifiInfo.linkSpeed
                }
            }
        }

        return NetworkStatusSnapshot(
            isConnected = hasInternet,
            transportLabel = transportLabel,
            isWifi = isWifi,
            isCellular = isCellular,
            isVpn = isVpn,
            isMetered = isMetered,
            isAirplaneMode = airplaneMode,
            wifiSsid = ssid,
            signalDbm = rssi,
            signalBars = bars,
            linkSpeedMbps = linkSpeed,
            downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upstreamKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
            localIpv4 = localIpv4,
            lastLatencyMs = lastLatencyMs,
            lastCheckedMs = System.currentTimeMillis()
        )
    }

    /**
     * Performs a real TCP reachability latency check to verify live internet connectivity.
     */
    suspend fun testConnectivityLatency(host: String = "8.8.8.8", port: Int = 53): NetworkActionOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val reachable = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 2600)
                }
                true
            }.getOrDefault(false)
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            val snap = readNetworkSnapshot(lastLatencyMs = if (reachable) elapsed else null)
            _status.value = snap
            if (reachable) {
                NetworkActionOutcome(
                    success = true,
                    summary = "Online (${snap.transportLabel}) · ${elapsed} ms ping",
                    detail = buildSummaryLine(snap) + " | Reachability to $host:$port verified in ${elapsed} ms.",
                    snapshot = snap
                )
            } else {
                NetworkActionOutcome(
                    success = false,
                    summary = "Internet unreachable (${snap.transportLabel})",
                    detail = buildSummaryLine(snap) + " | Connection test to $host:$port timed out.",
                    snapshot = snap
                )
            }
        }

    fun openWifiPanel(): NetworkActionOutcome {
        val snap = refreshStatus()
        val opened = launchPanelOrFallback(
            panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else null,
            fallbackAction = Settings.ACTION_WIFI_SETTINGS
        )
        return NetworkActionOutcome(
            success = opened,
            summary = if (opened) "Opened Android Wi-Fi quick panel" else "Could not open Wi-Fi panel",
            detail = if (opened) "Opened Wi-Fi toggle & network selector. Current status: ${buildSummaryLine(snap)}" else "No Wi-Fi settings activity available.",
            snapshot = snap
        )
    }

    fun openInternetPanel(): NetworkActionOutcome {
        val snap = refreshStatus()
        val opened = launchPanelOrFallback(
            panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else null,
            fallbackAction = Settings.ACTION_WIRELESS_SETTINGS
        )
        return NetworkActionOutcome(
            success = opened,
            summary = if (opened) "Opened Android Internet & Wi-Fi panel" else "Could not open Internet panel",
            detail = if (opened) "Opened unified Wi-Fi / Mobile Data connectivity sheet. Current: ${buildSummaryLine(snap)}" else "No Internet settings activity available.",
            snapshot = snap
        )
    }

    fun openNetworkSettings(section: String): NetworkActionOutcome {
        val snap = refreshStatus()
        val norm = section.trim().lowercase()
        val action = when {
            norm.contains("airplane") || norm.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            norm.contains("mobile") || norm.contains("cellular") || norm.contains("data") || norm.contains("roam") -> Settings.ACTION_DATA_ROAMING_SETTINGS
            norm.contains("vpn") -> Settings.ACTION_VPN_SETTINGS
            norm.contains("wifi") || norm.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            norm.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            else -> Settings.ACTION_WIRELESS_SETTINGS
        }
        val opened = launchPanelOrFallback(panelAction = null, fallbackAction = action)
        return NetworkActionOutcome(
            success = opened,
            summary = if (opened) "Opened $section network settings" else "Could not open $section settings",
            detail = if (opened) "Launched $action. Current: ${buildSummaryLine(snap)}" else "Settings activity $action unavailable on this device.",
            snapshot = snap
        )
    }

    private fun launchPanelOrFallback(panelAction: String?, fallbackAction: String): Boolean {
        if (panelAction != null) {
            val panelOk = runCatching {
                appContext.startActivity(Intent(panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (panelOk) return true
        }
        return runCatching {
            appContext.startActivity(Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun buildSummaryLine(snap: NetworkStatusSnapshot = refreshStatus()): String {
        val parts = mutableListOf<String>()
        parts += snap.transportLabel
        snap.wifiSsid?.let { parts += "SSID:$it" }
        snap.linkSpeedMbps?.let { parts += "${it} Mbps" }
        snap.signalDbm?.let { parts += "${it} dBm (${snap.signalBars}/4 bars)" }
        snap.localIpv4?.let { parts += "IP:$it" }
        if (snap.downstreamKbps > 0) {
            val downMbps = snap.downstreamKbps / 1000
            if (downMbps > 0) parts += "Cap:${downMbps}Mbps"
        }
        parts += if (snap.isMetered) "Metered" else "Unmetered"
        snap.lastLatencyMs?.let { parts += "Ping:${it}ms" }
        return parts.joinToString(" · ")
    }

    fun buildNetworkContextForAi(): String {
        val snap = refreshStatus()
        return "Live Network: ${buildSummaryLine(snap)}"
    }
}
