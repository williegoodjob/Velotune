package com.example.velotune.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class BluetoothTracker(
    private val context: Context,
    private val onDeviceStackChanged: (topDevice: BluetoothDevice?) -> Unit
) {

    // 已連線的藍牙裝置堆疊 (最新連線在最末尾)
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
                    // 若已存在先移除再加入，保證為堆疊最頂層
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

    fun getTopDevice(): BluetoothDevice? = connectedStack.lastOrNull()
}