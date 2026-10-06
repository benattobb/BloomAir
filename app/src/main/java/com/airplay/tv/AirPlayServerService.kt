package com.airplay.tv

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.airplay.tv.media.H264Decoder
import com.airplay.tv.ui.MainActivity
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.discovery.NsdServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface
import java.security.MessageDigest

class AirPlayServerService : Service(), RaopCallbackHandler, LogListener {

    private val tag = "AirPlayServerService"
    private val binder = LocalBinder()

    private var nsdManager: NsdServiceManager? = null
    private var nativeHandle: Long = 0L
    private var videoDecoder: H264Decoder? = null
    private var currentSurface: Surface? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: deviceName
        startServer(name)
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
    }

    fun startServer(tvName: String = "BloomAir TV") {
        if (nativeHandle != 0L) return
        deviceName = tvName

        acquirePowerAndWifiLocks()

        startForeground(
            AirPlayApp.NOTIFICATION_ID,
            buildNotification("BloomAir Server Ready: $deviceName"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, deviceName, keyFile, nohold = true, requirePin = false)
        if (nativeHandle == 0L) {
            Log.e(tag, "NativeBridge.nativeInit failed")
            _serverState.value = ServerState.STOPPED
            nsdManager?.release()
            nsdManager = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            releasePowerAndWifiLocks()
            stopSelf()
            return
        }

        NativeBridge.nativeSetAudioEnabled(nativeHandle, true)
        NativeBridge.nativeSetCodecs(nativeHandle, alac = true, aac = true)
        NativeBridge.nativeSetH265Enabled(nativeHandle, false)

        // Configure and start native low-latency Oboe audio engine with 60ms jitter cushion
        NativeBridge.nativeServerAudioConfigure(
            nativeHandle,
            cushionMs = 60,
            percentilePct = 95,
            oboeBufferFrames = 0,
            forceSwAlac = false,
            realtimePriority = true,
            lowLatency = true,
            benchmarkLog = false
        )
        NativeBridge.nativeServerAudioStart(nativeHandle)

        val port = NativeBridge.nativeStart(nativeHandle, 7000)
        if (port <= 0) {
            Log.e(tag, "Native AirPlay server failed to start (port=$port)")
            stopServer()
            return
        }
        Log.i(tag, "AirPlay native cryptographic server started on port $port")

        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = (NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()).toMutableMap().apply {
            // The native helper's template has a fixed example deviceid. Keep it aligned
            // with this receiver's unique RAOP identity or Apple senders may target a stale device.
            put("deviceid", hwAddr.joinToString(":") { "%02X".format(it) })
        }
        val raopServiceName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: (getMacHex(hwAddr) + "@" + deviceName)

        nsdManager?.registerRaop(raopServiceName, port, raopTxt)
        nsdManager?.registerAirplay(deviceName, port, airplayTxt)

        // A stopped receiver releases its codec. Recreate it when the activity still owns
        // a valid surface and the receiver is started again without recreating the activity.
        currentSurface?.takeIf { it.isValid }?.let { surface ->
            videoDecoder = H264Decoder(surface).apply { initialize(1920, 1080) }
        }

        _serverState.value = ServerState.LISTENING
        Log.i(tag, "BloomAir Service active for: $deviceName")
    }

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (nif in interfaces) {
                if (nif.isUp && (nif.name.startsWith("wlan") || nif.name.startsWith("eth"))) {
                    val mac = nif.hardwareAddress
                    if (mac != null && mac.size == 6 && !mac.all { it == 0.toByte() } &&
                        !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })) return mac
                }
            }
        } catch (_: Exception) {}

        // Android restricts hardware-address access on many TV builds. Use a stable,
        // per-device locally administered address instead of a shared hardcoded identity.
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: Build.FINGERPRINT
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 6).also { it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte() }
    }

    private fun getMacHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    fun attachSurface(surface: Surface) {
        // Release old decoder before creating new one (posts to codec thread internally)
        val old = videoDecoder
        videoDecoder = null
        old?.release()

        currentSurface = surface
        videoDecoder = H264Decoder(surface).apply {
            initialize(1920, 1080)
        }
    }

    fun detachSurface() {
        val old = videoDecoder
        videoDecoder = null
        currentSurface = null
        old?.release()
    }

    fun getFps(): Int = videoDecoder?.currentFps ?: 0

    fun stopServer() {
        nsdManager?.release()
        nsdManager = null

        if (nativeHandle != 0L) {
            NativeBridge.nativeServerAudioStop(nativeHandle)
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }

        val decoder = videoDecoder
        videoDecoder = null
        decoder?.release()

        _serverState.value = ServerState.STOPPED
        _connectedDeviceName.value = null
        _pinCode.value = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        releasePowerAndWifiLocks()
        stopSelf()
        Log.i(tag, "BloomAir Service stopped")
    }

    // --- RaopCallbackHandler Callbacks ---

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoDecoder?.decodeNalUnit(data, 0, data.size, ntpTimeNs / 1000, isH265)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        Log.i(tag, "onAudioFormat: ct=$ct, spf=$spf, usingScreen=$usingScreen")
        if (nativeHandle != 0L) {
            NativeBridge.nativeServerAudioStart(nativeHandle)
            NativeBridge.nativeServerAudioFormat(nativeHandle, ct, spf)
        }
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        Log.i(tag, "onVideoSize: src=${srcW}x${srcH} target=${w}x${h}")
        val width = if (w > 0) w.toInt() else 1920
        val height = if (h > 0) h.toInt() else 1080
        currentSurface?.let { surface ->
            videoDecoder?.initialize(width, height)
        }
    }

    override fun onVolumeChange(volume: Float) {
        Log.d(tag, "onVolumeChange: $volume")
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val linearVol = if (volume <= -144.0f) 0.0f
                            else if (volume <= 0.0f && volume >= -30.0f) Math.pow(10.0, volume / 30.0).toFloat()
                            else volume.coerceIn(0.0f, 1.0f)
            val targetVol = (linearVol * maxVol).toInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        } catch (e: Exception) {
            Log.w(tag, "Failed to adjust volume: ${e.message}")
        }
    }

    override fun onClientVolume(): Float = 1.0f

    override fun onAudioTeardown() {
        Log.i(tag, "onAudioTeardown")
        if (nativeHandle != 0L) {
            NativeBridge.nativeServerAudioStop(nativeHandle)
        }
    }

    override fun onConnectionInit() {
        Log.i(tag, "onConnectionInit - Apple device connected!")
        _serverState.value = ServerState.STREAMING
        _connectedDeviceName.value = "Apple Device"
        _pinCode.value = null
    }

    override fun onConnectionDestroy() {
        Log.i(tag, "onConnectionDestroy - Session ended")
        if (nativeHandle != 0L) {
            NativeBridge.nativeServerAudioStop(nativeHandle)
        }
        _serverState.value = ServerState.LISTENING
        _connectedDeviceName.value = null
        _pinCode.value = null
    }

    override fun onConnectionReset(reason: Int) {
        Log.i(tag, "onConnectionReset: $reason")
        if (nativeHandle != 0L) {
            NativeBridge.nativeServerAudioStop(nativeHandle)
        }
        _serverState.value = ServerState.LISTENING
        _connectedDeviceName.value = null
        _pinCode.value = null
    }

    override fun onDisplayPin(pin: String) {
        Log.i(tag, "Pairing PIN required: $pin")
        _pinCode.value = pin
    }

    override fun onMetadata(data: ByteArray) {}
    override fun onCoverArt(data: ByteArray) {}
    override fun onProgress(start: Long, curr: Long, end: Long) {}
    override fun onDacpId(dacpId: String, activeRemote: String) {}

    override fun onMirrorRunning(running: Boolean) {
        Log.i(tag, "onMirrorRunning: $running")
        _serverState.value = if (running) ServerState.STREAMING else ServerState.LISTENING
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {}
    override fun onVideoScrub(positionSeconds: Float) {}
    override fun onVideoRate(rate: Float) {}
    override fun onVideoStop() {}
    override fun onVideoSessionPoll() {}
    override fun onLog(msg: String) {
        Log.d(tag, "[Native] $msg")
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
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BloomAir:WakeLock").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
        }

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

    companion object {
        const val EXTRA_DEVICE_NAME = "com.airplay.tv.extra.DEVICE_NAME"
    }
}
