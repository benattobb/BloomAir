package com.airplay.tv.protocol

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Ultra-Low Latency AirPlay RTSP Server.
 * Optimized with TCP_NODELAY, 2MB network ring buffers, pre-cached plist responses,
 * and low audio-latency negotiation (< 40ms).
 */
class AirPlayRtspServer(
    private val port: Int = AirPlayConstants.DEFAULT_AIRPLAY_PORT,
    private val deviceName: String = "BloomAir TV",
    private val macAddress: String = "4A:2B:6C:8D:1E:0F"
) {

    private val tag = "AirPlayRtspServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Pre-cached info plist for instantaneous (< 1ms) GET /info responses
    private val cachedInfoPlistBytes: ByteArray by lazy {
        PlistHelper.buildServerInfoPlist(deviceName, macAddress).toByteArray(Charsets.UTF_8)
    }
    private val cachedPlaybackInfoBytes: ByteArray by lazy {
        PlistHelper.buildPlaybackInfo().toByteArray(Charsets.UTF_8)
    }

    var listener: SessionListener? = null
    var isRunning = false
        private set

    interface SessionListener {
        fun onSessionStarted(clientIp: String, userAgent: String)
        fun onSessionEnded()
        fun onVideoFrame(nalData: ByteArray, offset: Int, length: Int, pts: Long)
        fun onAudioPacket(pcmData: ByteArray, offset: Int, length: Int)
    }

    fun start() {
        if (isRunning) return

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port, 50).apply {
                    reuseAddress = true
                    receiveBufferSize = 2 * 1024 * 1024 // 2MB fast socket buffer
                }
                isRunning = true
                Log.i(tag, "AirPlay Low-Latency RTSP Server listening on port $port")

                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    // Optimize client socket latency immediately upon accept
                    configureLowLatencySocket(clientSocket)

                    launch {
                        // Boost thread priority for packet processing
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "RTSP server socket error", e)
                }
            } finally {
                isRunning = false
            }
        }
    }

    private fun configureLowLatencySocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true // Disable Nagle's algorithm to eliminate 40-100ms packet dispatch lag!
            socket.keepAlive = true
            socket.receiveBufferSize = 2 * 1024 * 1024 // 2MB high-throughput buffer
            socket.sendBufferSize = 512 * 1024
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY flag for lowest network packet latency
        } catch (e: SocketException) {
            Log.w(tag, "Could not set low latency socket options: ${e.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "Unknown"
        Log.i(tag, "Incoming connection from $clientIp:${socket.port}")

        var sessionId = UUID.randomUUID().toString()
        var clientUserAgent = "Apple Device"

        try {
            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            while (socket.isConnected && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isBlank()) continue

                val parts = requestLine.split(" ")
                if (parts.size < 2) continue

                val method = parts[0]
                val uri = parts[1]

                // Fast header parser
                val headers = mutableMapOf<String, String>()
                var headerLine = reader.readLine()
                var contentLength = 0

                while (!headerLine.isNullOrBlank()) {
                    val colonIdx = headerLine.indexOf(':')
                    if (colonIdx != -1) {
                        val key = headerLine.substring(0, colonIdx).trim().lowercase()
                        val value = headerLine.substring(colonIdx + 1).trim()
                        headers[key] = value
                        if (key == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                        if (key == "user-agent") {
                            clientUserAgent = value
                        }
                    }
                    headerLine = reader.readLine()
                }

                // Read binary body if present
                val bodyBytes = if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    var bytesRead = 0
                    while (bytesRead < contentLength) {
                        val count = input.read(buffer, bytesRead, contentLength - bytesRead)
                        if (count == -1) break
                        bytesRead += count
                    }
                    buffer
                } else {
                    ByteArray(0)
                }

                val cseq = headers["cseq"] ?: "0"

                // Fast dispatch RTSP methods
                when (method.uppercase()) {
                    "OPTIONS" -> {
                        sendRtspResponse(
                            output,
                            cseq,
                            "200 OK",
                            headers = mapOf(
                                "Public" to AirPlayConstants.RTSP_PUBLIC_METHODS,
                                "Server" to "AirTunes/220.68"
                            )
                        )
                    }

                    "GET" -> {
                        if (uri.contains("/info")) {
                            sendRtspResponse(
                                output,
                                cseq,
                                "200 OK",
                                headers = mapOf(
                                    "Content-Type" to "text/x-apple-plist+xml",
                                    "Server" to "AirTunes/220.68"
                                ),
                                body = cachedInfoPlistBytes
                            )
                        } else if (uri.contains("/playback-info")) {
                            sendRtspResponse(
                                output,
                                cseq,
                                "200 OK",
                                headers = mapOf(
                                    "Content-Type" to "text/x-apple-plist+xml"
                                ),
                                body = cachedPlaybackInfoBytes
                            )
                        } else {
                            sendRtspResponse(output, cseq, "200 OK")
                        }
                    }

                    "POST" -> {
                        if (uri.contains("/pair-setup") || uri.contains("/pair-verify") || uri.contains("/fp-setup")) {
                            val dummyVerifyResponse = ByteArray(bodyBytes.size.coerceAtLeast(32))
                            sendRtspResponse(
                                output,
                                cseq,
                                "200 OK",
                                headers = mapOf("Content-Type" to "application/octet-stream"),
                                body = dummyVerifyResponse
                            )
                        } else {
                            sendRtspResponse(output, cseq, "200 OK")
                        }
                    }

                    "SETUP" -> {
                        sessionId = UUID.randomUUID().toString()
                        listener?.onSessionStarted(clientIp, clientUserAgent)
                        sendRtspResponse(
                            output,
                            cseq,
                            "200 OK",
                            headers = mapOf(
                                "Session" to sessionId,
                                "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=7100"
                            )
                        )
                    }

                    "RECORD" -> {
                        // Negotiate ultra-low audio latency: 1764 samples (~40ms) instead of standard 11025 (~250ms)
                        sendRtspResponse(
                            output,
                            cseq,
                            "200 OK",
                            headers = mapOf(
                                "Session" to sessionId,
                                "Audio-Latency" to "1764"
                            )
                        )
                    }

                    "SET_PARAMETER" -> {
                        sendRtspResponse(output, cseq, "200 OK")
                    }

                    "TEARDOWN" -> {
                        sendRtspResponse(output, cseq, "200 OK")
                        listener?.onSessionEnded()
                        break
                    }

                    else -> {
                        sendRtspResponse(output, cseq, "200 OK")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Client session closed: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
            listener?.onSessionEnded()
        }
    }

    private fun sendRtspResponse(
        output: OutputStream,
        cseq: String,
        status: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ").append(status).append("\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")

        for ((key, value) in headers) {
            sb.append(key).append(": ").append(value).append("\r\n")
        }

        if (body != null && body.isNotEmpty()) {
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        } else {
            sb.append("Content-Length: 0\r\n")
        }
        sb.append("\r\n")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null && body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing RTSP server", e)
        }
        serverJob?.cancel()
        serverJob = null
        Log.i(tag, "AirPlay RTSP Server stopped")
    }
}
