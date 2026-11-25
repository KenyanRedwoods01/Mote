package com.mote.app.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

data class LanServerUiState(
    val isRunning: Boolean = false,
    val hostIp: String = "127.0.0.1",
    val port: Int = 11434,
    val requireBearerToken: Boolean = true,
    val bearerToken: String = "mote-local-key",
    val requestCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Real lightweight OpenAI-compatible local LAN HTTP endpoint inspired by `local-llms-on-android`.
 * Serves `/health` and `/v1/models` on the user's local network so desktop tools (e.g., Open WebUI
 * or curl) can discover active Mote cloud & loaded local GGUF models.
 */
class LocalLanApiServer(
    private val activeModelsProvider: () -> List<String>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _state = MutableStateFlow(
        LanServerUiState(hostIp = detectLocalIpv4())
    )
    val state: StateFlow<LanServerUiState> = _state.asStateFlow()

    fun updatePort(port: Int) {
        if (_state.value.isRunning) return
        _state.update { it.copy(port = port.coerceIn(1024, 65535), errorMessage = null) }
    }

    fun toggleRequireToken(require: Boolean) {
        _state.update { it.copy(requireBearerToken = require) }
    }

    fun toggleServer() {
        if (_state.value.isRunning) stop() else start()
    }

    fun start() {
        if (_state.value.isRunning) return
        val port = _state.value.port
        val ip = detectLocalIpv4()
        acceptJob = scope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                _state.update {
                    it.copy(isRunning = true, hostIp = ip, errorMessage = null)
                }
                while (isActive && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        errorMessage = e.localizedMessage ?: "Failed to bind port $port"
                    )
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _state.update { it.copy(isRunning = false) }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = parts.getOrNull(1) ?: "/"

            var authHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authHeader = line.substringAfter(":").trim()
                }
            }

            _state.update { it.copy(requestCount = it.requestCount + 1) }

            val current = _state.value
            val authorized = !current.requireBearerToken ||
                authHeader == "Bearer ${current.bearerToken}"

            val (status, body) = when {
                !authorized -> "401 Unauthorized" to """{"error":"Missing or invalid Bearer token"}"""
                path.startsWith("/v1/models") -> {
                    val modelsJson = activeModelsProvider().joinToString(",") { id ->
                        """{"id":"$id","object":"model","owned_by":"mote"}"""
                    }
                    "200 OK" to """{"object":"list","data":[$modelsJson]}"""
                }
                else -> {
                    "200 OK" to """{"status":"ok","service":"mote-android-lan-api","models_endpoint":"/v1/models"}"""
                }
            }

            OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8).use { writer ->
                writer.write("HTTP/1.1 $status\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write(body)
                writer.flush()
            }
        }
    }

    private fun detectLocalIpv4(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
