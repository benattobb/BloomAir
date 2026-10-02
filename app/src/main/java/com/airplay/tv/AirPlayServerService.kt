package com.airplay.tv

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.airplay.tv.discovery.BonjourAdvertiser
import com.airplay.tv.media.AudioStreamPlayer
import com.airplay.tv.media.H264Decoder
import com.airplay.tv.protocol.AirPlayRtspServer
import com.airplay.tv.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AirPlayServerService : Service() {

    private val tag = "AirPlayServerService"
    private val binder = LocalBinder()

    private var bonjourAdvertiser: BonjourAdvertiser? = null
    private var rtspServer: AirPlayRtspServer? = null
    private var videoDecoder: H264Decoder? = null
    private var audioPlayer: AudioStreamPlayer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    var deviceName: String = "BloomAir TV"
        private set

    enum class ServerState {
        STOPPED,
        LISTENING,
        STREAMING
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayServerService
            get() = this@AirPlayServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        acquirePowerAndWifiLocks()
    }

    fun startServer(tvName: String = "BloomAir TV") {
        deviceName = tvName
        startForeground(
            AirPlayApp.NOTIFICATION_ID,
            buildNotification("BloomAir Server Ready: $deviceName"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        // 1. Initialize mDNS Bonjour Advertiser
        bonjourAdvertiser = BonjourAdvertiser(this).apply {
            startAdvertising(deviceName)
        }

        // 2. Initialize Low-Latency RTSP Server
        rtspServer = AirPlayRtspServer(
            deviceName = deviceName,
            macAddress = bonjourAdvertiser?.getMacAddress() ?: "4A:2B:6C:8D:1E:0F"
        ).apply {
            listener = object : AirPlayRtspServer.SessionListener {
                override fun onSessionStarted(clientIp: String, userAgent: String) {
                    val friendlyName = parseSenderName(userAgent, clientIp)
                    _connectedDeviceName.value = friendlyName
                    _serverState.value = ServerState.STREAMING
                    audioPlayer?.start()
                }

                override fun onSessionEnded() {
                    _connectedDeviceName.value = null
                    _serverState.value = ServerState.LISTENING
                    audioPlayer?.stop()
                }

                override fun onVideoFrame(nalData: ByteArray, offset: Int, length: Int, pts: Long) {
                    videoDecoder?.decodeNalUnit(nalData, offset, length, pts)
                }

                override fun onAudioPacket(pcmData: ByteArray, offset: Int, length: Int) {
                    audioPlayer?.writePcmData(pcmData, offset, length)
                }
            }
            start()
        }

        // 3. Low-Latency Audio Streamer
        audioPlayer = AudioStreamPlayer()

        _serverState.value = ServerState.LISTENING
        Log.i(tag, "BloomAir Service active for: $deviceName")
    }

    fun attachSurface(surface: Surface) {
        videoDecoder?.release()
        videoDecoder = H264Decoder(surface).apply {
            initialize(1920, 1080)
        }
    }

    fun detachSurface() {
        videoDecoder?.release()
        videoDecoder = null
    }

    fun getFps(): Int = videoDecoder?.currentFps ?: 0

    fun stopServer() {
        bonjourAdvertiser?.stopAdvertising()
        bonjourAdvertiser = null

        rtspServer?.stop()
        rtspServer = null

        videoDecoder?.release()
        videoDecoder = null

        audioPlayer?.stop()
        audioPlayer = null

        _serverState.value = ServerState.STOPPED
        _connectedDeviceName.value = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(tag, "BloomAir Service stopped")
    }

    private fun parseSenderName(userAgent: String, fallbackIp: String): String {
        return when {
            userAgent.contains("iPad", ignoreCase = true) -> "Apple iPad ($fallbackIp)"
            userAgent.contains("Mac", ignoreCase = true) -> "Apple Mac ($fallbackIp)"
            userAgent.contains("iPhone", ignoreCase = true) -> "Apple iPhone ($fallbackIp)"
            else -> "Apple Device ($fallbackIp)"
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AirPlayApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_bloomair_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquirePowerAndWifiLocks() {
        // 1. Partial WakeLock so TV CPU doesn't throttle while streaming
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BloomAir:WakeLock").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
        }

        // 2. High-Performance / Low-Latency Wi-Fi Lock to eliminate Wi-Fi power-save polling latency
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(lockMode, "BloomAir:LowLatencyWifiLock").apply {
                acquire()
            }
        }
    }

    private fun releasePowerAndWifiLocks() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
        wifiLock?.let {
            if (it.isHeld) it.release()
            wifiLock = null
        }
    }

    override fun onDestroy() {
        stopServer()
        releasePowerAndWifiLocks()
        super.onDestroy()
    }
}
