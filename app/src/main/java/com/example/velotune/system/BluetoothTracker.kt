package com.example.velotune.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class BluetoothTracker(
    private val context: Context,
    private val onDeviceStackChanged: (topDevice: BluetoothDevice?) -> Unit
) {

    // 已連線藍牙裝置堆疊 (最新連線在最末尾)
    private val connectedStack = mutableListOf<BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            if (device == null) return

            when (intent?.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    connectedStack.removeAll { it.address == device.address }
                    connectedStack.add(device)
                    onDeviceStackChanged(connectedStack.lastOrNull())
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectedStack.removeAll { it.address == device.address }
                    onDeviceStackChanged(connectedStack.lastOrNull())
                }
            }
        }
    }

    private var isRegistered = false

    fun start() {
        if (!isRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            context.registerReceiver(receiver, filter)
            isRegistered = true
        }
        // 啟動時立即主動查詢目前已連線的設備！
        refreshCurrentlyConnectedDevices()
    }

    fun stop() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isRegistered = false
        }
    }

    /**
     * 主動查詢目前已經連線的藍牙音訊裝置 (A2DP 與 HEADSET)
     */
    @SuppressLint("MissingPermission")
    fun refreshCurrentlyConnectedDevices() {
        queryConnectedAudioDevices(context) { activeDevices ->
            for (dev in activeDevices) {
                if (connectedStack.none { it.address == dev.address }) {
                    connectedStack.add(dev)
                }
            }
            if (connectedStack.isNotEmpty()) {
                onDeviceStackChanged(connectedStack.lastOrNull())
            }
        }
    }

    fun getTopDevice(): BluetoothDevice? = connectedStack.lastOrNull()

    companion object {
        /**
         * 靜態方法：主動查詢當前已連線的音訊裝置 (供 Activity 與 Service 共用)
         */
        @SuppressLint("MissingPermission")
        fun queryConnectedAudioDevices(
            context: Context,
            onResult: (List<BluetoothDevice>) -> Unit
        ) {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                onResult(emptyList())
                return
            }

            val foundDevices = mutableListOf<BluetoothDevice>()
            val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            var pendingQueries = profiles.size

            for (profileType in profiles) {
                val hasProxy = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            val devices = proxy.connectedDevices
                            for (d in devices) {
                                if (foundDevices.none { it.address == d.address }) {
                                    foundDevices.add(d)
                                }
                            }
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                        } finally {
                            adapter.closeProfileProxy(profile, proxy)
                            pendingQueries--
                            if (pendingQueries == 0) {
                                onResult(foundDevices)
                            }
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        pendingQueries--
                        if (pendingQueries == 0) {
                            onResult(foundDevices)
                        }
                    }
                }, profileType)

                if (!hasProxy) {
                    pendingQueries--
                    if (pendingQueries == 0) {
                        onResult(foundDevices)
                    }
                }
            }
        }
    }
}