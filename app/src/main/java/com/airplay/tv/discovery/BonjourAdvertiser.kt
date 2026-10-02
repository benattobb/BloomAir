package com.airplay.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.airplay.tv.protocol.AirPlayConstants
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class BonjourAdvertiser(private val context: Context) {

    private val tag = "BonjourAdvertiser"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var airplayRegistrationListener: NsdManager.RegistrationListener? = null
    private var raopRegistrationListener: NsdManager.RegistrationListener? = null

    var isAdvertising = false
        private set

    fun startAdvertising(deviceName: String, port: Int = AirPlayConstants.DEFAULT_AIRPLAY_PORT) {
        if (isAdvertising) return

        try {
            // 1. Acquire Wi-Fi Multicast Lock to allow mDNS discovery packets
            acquireMulticastLock()

            val macAddress = getMacAddress()
            val formattedMac = macAddress.replace(":", "").uppercase(Locale.ROOT)

            // 2. Register AirPlay Service (_airplay._tcp)
            val airplayServiceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = AirPlayConstants.AIRPLAY_SERVICE_TYPE
                setPort(port)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("model", AirPlayConstants.AIRPLAY_MODEL)
                    setAttribute("features", AirPlayConstants.AIRPLAY_FEATURES)
                    setAttribute("deviceid", macAddress)
                    setAttribute("srcvers", AirPlayConstants.AIRPLAY_SRCVERS)
                    setAttribute("flags", AirPlayConstants.AIRPLAY_FLAGS)
                    setAttribute("pk", AirPlayConstants.AIRPLAY_PK)
                    setAttribute("pi", "b08f5a79-db29-4384-b456-a4784d9e6055")
                    setAttribute("vv", "2")
                }
            }

            airplayRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(tag, "AirPlay mDNS service registered: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(tag, "AirPlay mDNS registration failed: errorCode $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(tag, "AirPlay mDNS service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(tag, "AirPlay mDNS unregistration failed: errorCode $errorCode")
                }
            }

            nsdManager.registerService(
                airplayServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                airplayRegistrationListener
            )

            // 3. Register RAOP Service (_raop._tcp) for AirTunes audio streaming
            val raopServiceName = "${formattedMac}@${deviceName}"
            val raopServiceInfo = NsdServiceInfo().apply {
                serviceName = raopServiceName
                serviceType = AirPlayConstants.RAOP_SERVICE_TYPE
                setPort(port)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("txtvers", "1")
                    setAttribute("ch", "2")
                    setAttribute("cn", "0,1,2,3")
                    setAttribute("et", "0,3,5")
                    setAttribute("md", "0,1,2")
                    setAttribute("pw", "false")
                    setAttribute("sr", "44100")
                    setAttribute("ss", "16")
                    setAttribute("tp", "UDP")
                    setAttribute("vn", "65537")
                    setAttribute("vs", AirPlayConstants.AIRPLAY_SRCVERS)
                    setAttribute("am", AirPlayConstants.AIRPLAY_MODEL)
                    setAttribute("sf", AirPlayConstants.AIRPLAY_FLAGS)
                }
            }

            raopRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(tag, "RAOP mDNS service registered: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(tag, "RAOP mDNS registration failed: errorCode $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(tag, "RAOP mDNS service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(tag, "RAOP mDNS unregistration failed: errorCode $errorCode")
                }
            }

            nsdManager.registerService(
                raopServiceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                raopRegistrationListener
            )

            isAdvertising = true
            Log.i(tag, "Started Bonjour advertising for device: $deviceName on port $port")

        } catch (e: Exception) {
            Log.e(tag, "Error starting Bonjour advertiser", e)
        }
    }

    fun stopAdvertising() {
        if (!isAdvertising) return

        try {
            airplayRegistrationListener?.let {
                nsdManager.unregisterService(it)
                airplayRegistrationListener = null
            }
            raopRegistrationListener?.let {
                nsdManager.unregisterService(it)
                raopRegistrationListener = null
            }
            releaseMulticastLock()
            isAdvertising = false
            Log.i(tag, "Stopped Bonjour advertising")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Bonjour advertiser", e)
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("AirPlayTVMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
            }
            multicastLock = null
        }
    }

    fun getMacAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("wlan0", ignoreCase = true) || intf.name.equals("eth0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: continue
                    val buf = StringBuilder()
                    for (b in mac) {
                        buf.append(String.format("%02X:", b))
                    }
                    if (buf.isNotEmpty()) {
                        buf.deleteCharAt(buf.length - 1)
                    }
                    return buf.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Unable to read hardware MAC address: ${e.message}")
        }
        return "4A:2B:6C:8D:1E:0F" // Default synthetic MAC if prohibited by Android sandboxing
    }
}
