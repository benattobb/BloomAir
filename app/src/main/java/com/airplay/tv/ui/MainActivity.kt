package com.airplay.tv.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airplay.tv.AirPlayServerService
import com.airplay.tv.R
import com.airplay.tv.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var airPlayService: AirPlayServerService? = null
    private var isBound = false
    private var isSurfaceCreated = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirPlayServerService.LocalBinder
            airPlayService = binder.service
            isBound = true

            if (isSurfaceCreated) {
                airPlayService?.attachSurface(binding.videoSurfaceView.holder.surface)
            }

            observeServiceState()

            // Auto-start the server on TV launch
            if (airPlayService?.serverState?.value == AirPlayServerService.ServerState.STOPPED) {
                val tvName = prefs.getString("tv_name", "BloomAir TV") ?: "BloomAir TV"
                airPlayService?.startServer(tvName)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            airPlayService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("airplay_prefs", Context.MODE_PRIVATE)

        setupSurfaceView()
        setupUI()
        startAndBindService()
    }

    private fun setupSurfaceView() {
        binding.videoSurfaceView.holder.addCallback(this)
    }

    private fun setupUI() {
        val tvName = prefs.getString("tv_name", "BloomAir TV") ?: "BloomAir TV"
        binding.txtDeviceName.text = tvName
        updateInstructionText(tvName)

        binding.btnToggleServer.setOnClickListener {
            toggleServer()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Set initial D-Pad focus for Android TV remote
        binding.btnToggleServer.requestFocus()
    }

    private fun updateInstructionText(tvName: String) {
        binding.txtInstruction.text = "Open Control Center on your iPad or Mac, select Screen Mirroring, and choose $tvName."
    }

    private fun startAndBindService() {
        val intent = Intent(this, AirPlayServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            airPlayService?.serverState?.collectLatest { state ->
                runOnUiThread {
                    when (state) {
                        AirPlayServerService.ServerState.LISTENING -> {
                            binding.txtStatusBadge.text = "READY TO CONNECT"
                            binding.txtStatusBadge.setTextColor(getColor(R.color.accent_sage))
                            binding.dotStatus.backgroundTintList = getColorStateList(R.color.accent_sage)
                            binding.btnToggleServer.text = "Stop Receiver"
                            binding.dashboardOverlay.visibility = View.VISIBLE
                            binding.videoSurfaceView.visibility = View.GONE
                        }
                        AirPlayServerService.ServerState.STREAMING -> {
                            binding.txtStatusBadge.text = "STREAMING ACTIVE"
                            binding.txtStatusBadge.setTextColor(getColor(R.color.text_primary))
                            binding.dotStatus.backgroundTintList = getColorStateList(R.color.status_green)
                            binding.btnToggleServer.text = "Stop Receiver"
                            // Auto transition to video surface for mirroring
                            binding.dashboardOverlay.visibility = View.GONE
                            binding.videoSurfaceView.visibility = View.VISIBLE
                        }
                        AirPlayServerService.ServerState.STOPPED -> {
                            binding.txtStatusBadge.text = "RECEIVER STOPPED"
                            binding.txtStatusBadge.setTextColor(getColor(R.color.text_muted))
                            binding.dotStatus.backgroundTintList = getColorStateList(R.color.status_red)
                            binding.btnToggleServer.text = "Start Receiver"
                            binding.dashboardOverlay.visibility = View.VISIBLE
                            binding.videoSurfaceView.visibility = View.GONE
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            airPlayService?.connectedDeviceName?.collectLatest { deviceName ->
                runOnUiThread {
                    if (deviceName != null) {
                        binding.txtSenderDevice.text = "Connected: $deviceName"
                        binding.txtSenderDevice.setTextColor(getColor(R.color.accent_sage))
                    } else {
                        binding.txtSenderDevice.text = "Waiting for connection..."
                        binding.txtSenderDevice.setTextColor(getColor(R.color.text_muted))
                    }
                }
            }
        }
    }

    private fun toggleServer() {
        val service = airPlayService ?: return
        if (service.serverState.value == AirPlayServerService.ServerState.STOPPED) {
            val tvName = prefs.getString("tv_name", "BloomAir TV") ?: "BloomAir TV"
            service.startServer(tvName)
        } else {
            service.stopServer()
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_AirPlayTV)
            .setView(dialogView)
            .create()

        dialogView.findViewById<TextView>(R.id.dialogTxtIp)?.text = getLocalIpAddress()

        dialogView.findViewById<Button>(R.id.dialogBtnRename)?.setOnClickListener {
            dialog.dismiss()
            showRenameDialog()
        }

        dialogView.findViewById<Button>(R.id.dialogBtnVideoSurface)?.setOnClickListener {
            dialog.dismiss()
            toggleFullscreenView()
        }

        dialogView.findViewById<Button>(R.id.dialogBtnClose)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()

        // Focus the Done button by default for easy TV remote navigation
        dialogView.findViewById<Button>(R.id.dialogBtnClose)?.requestFocus()
    }

    private fun toggleFullscreenView() {
        if (binding.dashboardOverlay.visibility == View.VISIBLE) {
            binding.dashboardOverlay.visibility = View.GONE
            binding.videoSurfaceView.visibility = View.VISIBLE
        } else {
            binding.dashboardOverlay.visibility = View.VISIBLE
            binding.btnToggleServer.requestFocus()
        }
    }

    private fun showRenameDialog() {
        val currentName = prefs.getString("tv_name", "BloomAir TV") ?: "BloomAir TV"
        val input = EditText(this).apply {
            setText(currentName)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            setSelection(currentName.length)
        }

        AlertDialog.Builder(this, R.style.Theme_AirPlayTV)
            .setTitle("Change TV AirPlay Name")
            .setMessage("This name appears on your iPad and Mac in Screen Mirroring.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("tv_name", newName).apply()
                    binding.txtDeviceName.text = newName
                    updateInstructionText(newName)
                    airPlayService?.stopServer()
                    airPlayService?.startServer(newName)
                    Toast.makeText(this, "TV name updated to $newName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Pressing BACK or MENU during streaming restores the dashboard overlay
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.dashboardOverlay.visibility == View.GONE) {
            binding.dashboardOverlay.visibility = View.VISIBLE
            binding.btnToggleServer.requestFocus()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            toggleFullscreenView()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceCreated = true
        airPlayService?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceCreated = false
        airPlayService?.detachSurface()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        return "192.168.1.xxx"
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
