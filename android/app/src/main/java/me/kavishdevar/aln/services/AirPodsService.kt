/*
 * AirPods like Normal (ALN) - Bringing Apple-only features to Linux and Android for seamless AirPods functionality!
 * 
 * Copyright (C) 2024 Kavish Devar
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package me.kavishdevar.aln.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import me.kavishdevar.aln.BatteryWidget
import me.kavishdevar.aln.MainActivity
import me.kavishdevar.aln.R
import me.kavishdevar.aln.utils.AirPodsNotifications
import me.kavishdevar.aln.utils.Battery
import me.kavishdevar.aln.utils.BatteryComponent
import me.kavishdevar.aln.utils.BatteryStatus
import me.kavishdevar.aln.utils.CrossDevice
import me.kavishdevar.aln.utils.CrossDevicePackets
import me.kavishdevar.aln.utils.Enums
import me.kavishdevar.aln.utils.LongPressPackets
import me.kavishdevar.aln.utils.MediaController
import me.kavishdevar.aln.utils.Window
import org.lsposed.hiddenapibypass.HiddenApiBypass

object ServiceManager {
    private var service: AirPodsService? = null
    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }
    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Synchronized
    fun restartService(context: Context) {
        service?.stopSelf()
        Log.d("ServiceManager", "Restarting service, service is null: ${service == null}")
        val intent = Intent(context, AirPodsService::class.java)
        context.stopService(intent)
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            context.startService(intent)
            context.startActivity(Intent(context, MainActivity::class.java))
            service?.clearLogs()
        }
    }
}

//@Suppress("unused")
class AirPodsService: Service() {
    private var macAddress = ""
    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow


    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("packet_logs", MODE_PRIVATE)
    }

    private fun logPacket(packet: ByteArray, source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"
        val logs = sharedPreferences.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        logs.add(logEntry)
        _packetLogsFlow.value = logs
        sharedPreferences.edit().putStringSet(packetLogKey, logs).apply()
    }

    fun getPacketLogs(): Set<String> {
        return sharedPreferences.getStringSet(packetLogKey, emptySet()) ?: emptySet()
    }

    private fun clearPacketLogs() {
        sharedPreferences.edit().remove(packetLogKey).apply()
    }

    fun clearLogs() {
        clearPacketLogs() // Expose a method to clear logs
        _packetLogsFlow.value = emptySet()
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    var popupShown = false

    fun showPopup(service: Service, name: String) {
        if (popupShown) {
            return
        }
        val window = Window(service.applicationContext)
        window.open(name, batteryNotification)
        popupShown = true
    }

    @Suppress("ClassName")
    private object bluetoothReceiver: BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
                }
            val action = intent.action
            val context = context?.applicationContext
            val name = context?.getSharedPreferences("settings", MODE_PRIVATE)?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && action != null && !action.isEmpty()) {
                Log.d("AirPodsService", "Received bluetooth connection broadcast")
                if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    if (ServiceManager.getService()?.isConnectedLocally == true) {
                        ServiceManager.getService()?.manuallyCheckForAudioSource()
                        return
                    }
                    val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
                    bluetoothDevice.fetchUuidsWithSdp()
                    if (bluetoothDevice.uuids != null) {
                        if (bluetoothDevice.uuids.contains(uuid)) {
                            val intent =
                                Intent(AirPodsNotifications.Companion.AIRPODS_CONNECTION_DETECTED)
                            intent.putExtra("name", name)
                            intent.putExtra("device", bluetoothDevice)
                            context?.sendBroadcast(intent)
                        }
                    }
                }
            }
        }
    }

    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver

    @SuppressLint("MissingPermission")
    fun scanForAirPods(bluetoothAdapter: BluetoothAdapter): Flow<List<ScanResult>> = callbackFlow {
        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            ?: throw IllegalStateException("Bluetooth adapter unavailable")

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device != null) {
                    trySend(listOf(result))
                }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                trySend(results)
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("Scan failed with error: $errorCode"))
            }
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanFilters = listOf<ScanFilter>(
            ScanFilter.Builder()
                .setManufacturerData(0x004C, byteArrayOf())
                .build()
        )

        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)
        awaitClose { bluetoothLeScanner.stopScan(scanCallback) }
    }


    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val notificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods)
            .setContentTitle("AirPods not connected")
            .setContentText("Tap to open app")
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.Companion.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
        })
    }

    fun sendBatteryBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.Companion.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also {
            it.setTextViewText(
                R.id.left_battery_widget,
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }?.let {
                    "${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.left_battery_progress,
                100,
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }?.level ?: 0,
                false
            )
            it.setTextViewText(
                R.id.right_battery_widget,
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }?.let {
                    "${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.right_battery_progress,
                100,
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }?.level ?: 0,
                false
            )
            it.setTextViewText(
                R.id.case_battery_widget,
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }?.let {
                    "${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                } ?: ""
            )
            it.setProgressBar(
                R.id.case_battery_progress,
                100,
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }?.level ?: 0,
                false
            )
        }
        Log.d("AirPodsService", "Updating battery widget")
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        var updatedNotification: Notification? = null

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (connected) {
            updatedNotification = NotificationCompat.Builder(this, "background_service_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle(airpodsName)
                .setContentText("""${batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                    if (it.status != BatteryStatus.DISCONNECTED) {
                        "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                    } else {
                        ""
                    }
                } ?: ""} ${batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                    if (it.status != BatteryStatus.DISCONNECTED) {
                        "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                    } else {
                        ""
                    }
                } ?: ""} ${batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                    if (it.status != BatteryStatus.DISCONNECTED) {
                        "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                    } else {
                        ""
                    }
                } ?: ""}""")
                    .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        } else {
            updatedNotification = NotificationCompat.Builder(this, "background_service_status")
                .setSmallIcon(R.drawable.airpods)
                .setContentTitle("AirPods not connected")
                .setContentText("Tap to open app")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }

        notificationManager.notify(1, updatedNotification)
    }

    private lateinit var connectionReceiver: BroadcastReceiver
    private lateinit var disconnectionReceiver: BroadcastReceiver

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AirPodsService", "Service started")
        ServiceManager.setService(this)
        startForegroundNotification()

        Log.d("AirPodsService", "Initializing CrossDevice")
        CrossDevice.init(this)
        Log.d("AirPodsService", "CrossDevice initialized")

        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        connectionReceiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.Companion.AIRPODS_CONNECTION_DETECTED) {
                    device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)!!
                    } else {
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }
                    val name = this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE)
                        .getString("name", device?.name)
                    if (this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE).getString("name", null) == null) {
                        this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE).edit()
                            .putString("name", name).apply()
                    }
                    Log.d("AirPodsQuickSwitchServices", CrossDevice.isAvailable.toString())
                    if (!CrossDevice.checkAirPodsConnectionStatus()) {
                        Log.d("AirPodsService", "$name connected")
                        showPopup(this@AirPodsService, name.toString())
                        connectToSocket(device!!)
                        isConnectedLocally = true
                        macAddress = device!!.address
                        updateNotificationContent(true, name.toString(), batteryNotification.getBattery())
                    }
                }
                else if (intent?.action == AirPodsNotifications.Companion.AIRPODS_DISCONNECTED) {
                    device = null
                    isConnectedLocally = false
                    popupShown = false
                    updateNotificationContent(false)
                }
            }
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.Companion.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.Companion.AIRPODS_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(connectionReceiver, deviceIntentFilter)
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (bluetoothAdapter.isEnabled) {
            CoroutineScope(Dispatchers.IO).launch {
                var lastData = byteArrayOf()
                scanForAirPods(bluetoothAdapter).collect { scanResults ->
                    scanResults.forEach { scanResult ->
                        val device = scanResult.device
                        device.fetchUuidsWithSdp()
                        val manufacturerData = scanResult.scanRecord?.manufacturerSpecificData?.get(0x004C)
                        if (manufacturerData != null && manufacturerData != lastData) {
                            lastData = manufacturerData
                            val formattedHex = manufacturerData.joinToString(" ") { "%02X".format(it) }
                            val rssi = scanResult.rssi
                            Log.d(
                                "AirPodsBLEService",
                                "Received broadcast of size ${manufacturerData.size} from ${device.address} | $rssi | $formattedHex"
                            )
                        }
                    }
                }
            }
        }

        bluetoothAdapter.bondedDevices.forEach { device ->
            device.fetchUuidsWithSdp()
            if (device.uuids != null)
            {
                if (device.uuids.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))) {
                    bluetoothAdapter.getProfileProxy(
                        this,
                        object : BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                if (profile == BluetoothProfile.A2DP) {
                                    val connectedDevices = proxy.connectedDevices
                                    if (connectedDevices.isNotEmpty()) {
                                        if (!CrossDevice.checkAirPodsConnectionStatus()) {
                                            connectToSocket(device)
                                        }
                                        this@AirPodsService.sendBroadcast(
                                            Intent(AirPodsNotifications.Companion.AIRPODS_CONNECTED)
                                        )
                                    }
                                }
                                bluetoothAdapter.closeProfileProxy(profile, proxy)
                            }

                            override fun onServiceDisconnected(profile: Int) {}
                        },
                        BluetoothProfile.A2DP
                    )
                }
            }
        }

        if (!isConnectedLocally && !CrossDevice.isAvailable) {
            clearPacketLogs() // Clear logs when device is not available
        }

        return START_STICKY
    }

    private lateinit var socket: BluetoothSocket

    fun manuallyCheckForAudioSource() {
        if (earDetectionNotification.status[0] != 0.toByte() && earDetectionNotification.status[1] != 0.toByte()) {
            Log.d("AirPodsService", "For some reason, Android connected to the audio profile itself even after disconnecting. Disconnecting audio profile again!")
            disconnectAudio(this, device)
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(device: BluetoothDevice) {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothSocket;")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        if (isConnectedLocally != true) {
            try {
                socket = HiddenApiBypass.newInstance(
                    BluetoothSocket::class.java,
                    3,
                    true,
                    true,
                    device,
                    0x1001,
                    uuid
                ) as BluetoothSocket
            } catch (
                e: Exception
            ) {
                e.printStackTrace()
                try {
                    socket = HiddenApiBypass.newInstance(
                        BluetoothSocket::class.java,
                        3,
                        1,
                        true,
                        true,
                        device,
                        0x1001,
                        uuid
                    ) as BluetoothSocket
                } catch (
                    e: Exception
                ) {
                    e.printStackTrace()
                }
            }

            try {
                socket.connect()
                this@AirPodsService.device = device
                isConnectedLocally = true
                socket.let { it ->
                    // sometimes doesn't work ;-;
                    // i though i move it to the coroutine
                    // but, the socket sometimes disconnects if i don't send a packet outside of the routine first
                    // so, sending *again*, with a delay, in the coroutine
                    it.outputStream.write(Enums.HANDSHAKE.value)
                    it.outputStream.flush()
                    it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                    it.outputStream.flush()
                    it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                    it.outputStream.flush()
                    CoroutineScope(Dispatchers.IO).launch {
                        // this is so stupid, why does it disconnect if i don't send a packet outside of the coroutine first
                        it.outputStream.write(Enums.HANDSHAKE.value)
                        it.outputStream.flush()
                        delay(200)
                        it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                        it.outputStream.flush()
                        delay(200)
                        it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                        it.outputStream.flush()
                        delay(200)
                        // just in case this doesn't work, send all three after 5 seconds again
                        Handler(Looper.getMainLooper()).postDelayed({
                            it.outputStream.write(Enums.HANDSHAKE.value)
                            it.outputStream.flush()
                            it.outputStream.write(Enums.SET_SPECIFIC_FEATURES.value)
                            it.outputStream.flush()
                            it.outputStream.write(Enums.REQUEST_NOTIFICATIONS.value)
                            it.outputStream.flush()
                        }, 5000)
                        sendBroadcast(
                            Intent(AirPodsNotifications.Companion.AIRPODS_CONNECTED)
                                .putExtra("device", device)
                        )
                        while (socket.isConnected == true) {
                            socket.let {
                                val audioManager =
                                    this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
                                MediaController.initialize(audioManager, this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE))
                                val buffer = ByteArray(1024)
                                val bytesRead = it.inputStream.read(buffer)
                                var data: ByteArray = byteArrayOf()
                                if (bytesRead > 0) {
                                    data = buffer.copyOfRange(0, bytesRead)
                                    logPacket(data, "AirPods")
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.AIRPODS_DATA).apply {
                                        putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    })
                                    val bytes = buffer.copyOfRange(0, bytesRead)
                                    val formattedHex = bytes.joinToString(" ") { "%02X".format(it) }
                                    CrossDevice.sendReceivedPacket(bytes)
                                    Log.d("AirPods Data", "Data received: $formattedHex")
                                } else if (bytesRead == -1) {
                                    Log.d("AirPods Service", "Socket closed (bytesRead = -1)")
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.AIRPODS_DISCONNECTED))
                                    return@launch
                                }
                                var inEar = false
                                var inEarData = listOf<Boolean>()
                                if (earDetectionNotification.isEarDetectionData(data)) {
                                    earDetectionNotification.setStatus(data)
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.EAR_DETECTION_DATA).apply {
                                        val list = earDetectionNotification.status
                                        val bytes = ByteArray(2)
                                        bytes[0] = list[0]
                                        bytes[1] = list[1]
                                        putExtra("data", bytes)
                                    })
                                    Log.d(
                                        "AirPods Parser",
                                        "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                                    )
                                    var justEnabledA2dp = false
                                    earReceiver = object : BroadcastReceiver() {
                                        override fun onReceive(context: Context, intent: Intent) {
                                            val data = intent.getByteArrayExtra("data")
                                            if (data != null && earDetectionEnabled) {
                                                inEar =
                                                    if (data.find { it == 0x02.toByte() } != null || data.find { it == 0x03.toByte() } != null) {
                                                        data[0] == 0x00.toByte() || data[1] == 0x00.toByte()
                                                    } else {
                                                        data[0] == 0x00.toByte() && data[1] == 0x00.toByte()
                                                    }

                                                val newInEarData = listOf(
                                                    data[0] == 0x00.toByte(),
                                                    data[1] == 0x00.toByte()
                                                )
                                                if (newInEarData.contains(true) && inEarData == listOf(
                                                        false,
                                                        false
                                                    )
                                                ) {
                                                    connectAudio(this@AirPodsService, device)
                                                    justEnabledA2dp = true
                                                    val bluetoothAdapter =
                                                        this@AirPodsService.getSystemService(
                                                            BluetoothManager::class.java
                                                        ).adapter
                                                    bluetoothAdapter.getProfileProxy(
                                                        this@AirPodsService,
                                                        object : BluetoothProfile.ServiceListener {
                                                            override fun onServiceConnected(
                                                                profile: Int,
                                                                proxy: BluetoothProfile
                                                            ) {
                                                                if (profile == BluetoothProfile.A2DP) {
                                                                    val connectedDevices =
                                                                        proxy.connectedDevices
                                                                    if (connectedDevices.isNotEmpty()) {
                                                                        MediaController.sendPlay()
                                                                    }
                                                                }
                                                                bluetoothAdapter.closeProfileProxy(
                                                                    profile,
                                                                    proxy
                                                                )
                                                            }

                                                            override fun onServiceDisconnected(
                                                                profile: Int
                                                            ) {
                                                            }
                                                        },
                                                        BluetoothProfile.A2DP
                                                    )

                                                } else if (newInEarData == listOf(false, false)) {
                                                    disconnectAudio(this@AirPodsService, device)
                                                }

                                                if (inEarData.contains(false) && newInEarData == listOf(
                                                        true,
                                                        true
                                                    )
                                                ) {
                                                    Log.d("AirPods Parser", "User put in both AirPods from just one.")
                                                    MediaController.userPlayedTheMedia = false
                                                }
                                                if (newInEarData.contains(false) && inEarData == listOf(
                                                        true,
                                                        true
                                                    )
                                                ) {
                                                    Log.d("AirPods Parser", "User took one of two out.")
                                                    MediaController.userPlayedTheMedia = false
                                                }

                                                Log.d(
                                                    "AirPods Parser",
                                                    "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
                                                )
                                                if (newInEarData.sorted() == inEarData.sorted()) {
                                                    Log.d("AirPods Parser", "hi")
                                                    return
                                                }
                                                Log.d("AirPods Parser", "this shouldn't be run if the last log was 'hi'.")

                                                inEarData = newInEarData

                                                if (inEar == true) {
                                                    if (!justEnabledA2dp) {
                                                        justEnabledA2dp = false
                                                        MediaController.sendPlay()
                                                        MediaController.iPausedTheMedia = false
                                                    }
                                                } else {
                                                        MediaController.sendPause()
                                                }
                                            }
                                        }
                                    }

                                    val earIntentFilter =
                                        IntentFilter(AirPodsNotifications.Companion.EAR_DETECTION_DATA)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        this@AirPodsService.registerReceiver(
                                            earReceiver, earIntentFilter,
                                            RECEIVER_EXPORTED
                                        )
                                    } else {
                                        this@AirPodsService.registerReceiver(
                                            earReceiver,
                                            earIntentFilter
                                        )
                                    }
                                } else if (ancNotification.isANCData(data)) {
                                    CrossDevice.sendRemotePacket(data)
                                    CrossDevice.ancBytes = data
                                    ancNotification.setStatus(data)
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.ANC_DATA).apply {
                                        putExtra("data", ancNotification.status)
                                    })
                                    Log.d("AirPods Parser", "ANC: ${ancNotification.status}")
                                } else if (batteryNotification.isBatteryData(data)) {
                                    CrossDevice.sendRemotePacket(data)
                                    CrossDevice.batteryBytes = data
                                    batteryNotification.setBattery(data)
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.BATTERY_DATA).apply {
                                        putParcelableArrayListExtra(
                                            "data",
                                            ArrayList(batteryNotification.getBattery())
                                        )
                                    })
                                    updateBatteryWidget()
                                    updateNotificationContent(
                                        true,
                                        this@AirPodsService.getSharedPreferences(
                                            "settings",
                                            MODE_PRIVATE
                                        ).getString("name", device.name),
                                        batteryNotification.getBattery()
                                    )
                                    for (battery in batteryNotification.getBattery()) {
                                        Log.d(
                                            "AirPods Parser",
                                            "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                                        )
                                    }
                                    if (batteryNotification.getBattery()[0].status == 1 && batteryNotification.getBattery()[1].status == 1) {
                                        disconnectAudio(this@AirPodsService, device)
                                    } else {
                                        connectAudio(this@AirPodsService, device)
                                    }
                                } else if (conversationAwarenessNotification.isConversationalAwarenessData(
                                        data
                                    )
                                ) {
                                    conversationAwarenessNotification.setData(data)
                                    sendBroadcast(Intent(AirPodsNotifications.Companion.CA_DATA).apply {
                                        putExtra("data", conversationAwarenessNotification.status)
                                    })


                                    if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                                        MediaController.startSpeaking()
                                    } else if (conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                                        MediaController.stopSpeaking()
                                    }

                                    Log.d(
                                        "AirPods Parser",
                                        "Conversation Awareness: ${conversationAwarenessNotification.status}"
                                    )
                                } else {
                                }
                            }
                        }
                        Log.d("AirPods Service", "Socket closed")
                        isConnectedLocally = false
                        socket.close()
                        sendBroadcast(Intent(AirPodsNotifications.Companion.AIRPODS_DISCONNECTED))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.d("AirPodsService", "Failed to connect to socket")
            }
        }
    }

    fun disconnect() {
        socket.close()
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
        HiddenApiBypass.invoke(BluetoothDevice::class.java, device, "disconnect")
        isConnectedLocally = false
    }

    fun sendPacket(packet: String) {
        val fromHex = packet.split(" ").map { it.toInt(16).toByte() }
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            CrossDevice.sendRemotePacket(CrossDevicePackets.AIRPODS_DATA_HEADER.packet + fromHex.toByteArray())
            return
        }
        if (this::socket.isInitialized) {
            val byteArray = fromHex.toByteArray()
            socket.outputStream?.write(byteArray)
            socket.outputStream?.flush()
            logPacket(byteArray, "Sent")
        }
    }

    fun sendPacket(packet: ByteArray) {
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            CrossDevice.sendRemotePacket(CrossDevicePackets.AIRPODS_DATA_HEADER.packet + packet)
            return
        }
        if (this::socket.isInitialized) {
            socket.outputStream?.write(packet)
            socket.outputStream?.flush()
            logPacket(packet, "Sent")
        }
    }

    fun setANCMode(mode: Int) {
        Log.d("AirPodsService", "setANCMode: $mode")
        when (mode) {
            1 -> {
                sendPacket(Enums.NOISE_CANCELLATION_OFF.value)
            }
            2 -> {
                sendPacket(Enums.NOISE_CANCELLATION_ON.value)
            }
            3 -> {
                sendPacket(Enums.NOISE_CANCELLATION_TRANSPARENCY.value)
            }
            4 -> {
                sendPacket(Enums.NOISE_CANCELLATION_ADAPTIVE.value)
            }
        }
    }

    fun setCAEnabled(enabled: Boolean) {
        sendPacket(if (enabled) Enums.SET_CONVERSATION_AWARENESS_ON.value else Enums.SET_CONVERSATION_AWARENESS_OFF.value)
    }

    fun setOffListeningMode(enabled: Boolean) {
        sendPacket(byteArrayOf(0x04, 0x00 ,0x04, 0x00, 0x09, 0x00, 0x34, if (enabled) 0x01 else 0x02, 0x00, 0x00, 0x00))
    }

    fun setAdaptiveStrength(strength: Int) {
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2E, strength.toByte(), 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setPressSpeed(speed: Int) {
        // 0x00 = default, 0x01 = slower, 0x02 = slowest
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x17, speed.toByte(), 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setPressAndHoldDuration(speed: Int) {
        // 0 - default, 1 - slower, 2 - slowest
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x18, speed.toByte(), 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setVolumeSwipeSpeed(speed: Int) {
        // 0 - default, 1 - longer, 2 - longest
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x23, speed.toByte(), 0x00, 0x00, 0x00)
        Log.d("AirPodsService", "Setting volume swipe speed to $speed by packet ${bytes.joinToString(" ") { "%02X".format(it) }}")
        sendPacket(bytes)
    }

    fun setNoiseCancellationWithOnePod(enabled: Boolean) {
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x1B, if (enabled) 0x01 else 0x02, 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setVolumeControl(enabled: Boolean) {
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x25, if (enabled) 0x01 else 0x02, 0x00, 0x00, 0x00)
        sendPacket(bytes)
    }

    fun setToneVolume(volume: Int) {
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x1F, volume.toByte(), 0x50, 0x00, 0x00)
        sendPacket(bytes)
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification = AirPodsNotifications.ConversationalAwarenessNotification()

    var earDetectionEnabled = true

    fun setEarDetection(enabled: Boolean) {
        earDetectionEnabled = enabled
    }

    fun getBattery(): List<Battery> {
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            batteryNotification.setBattery(CrossDevice.batteryBytes)
        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
        if (!isConnectedLocally && CrossDevice.isAvailable) {
            ancNotification.setStatus(CrossDevice.ancBytes)
        }
        return ancNotification.status
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)
    }

    fun setName(name: String) {
        val nameBytes = name.toByteArray()
        val bytes = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x1a, 0x00, 0x01,
            nameBytes.size.toByte(), 0x00) + nameBytes
        sendPacket(bytes)
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d("AirPodsService", "setName: $name, sent packet: $hex")
    }

    fun setPVEnabled(enabled: Boolean) {
        var hex = "04 00 04 00 09 00 26 ${if (enabled) "01" else "02"} 00 00 00"
        var bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)
        hex = "04 00 04 00 17 00 00 00 10 00 12 00 08 E${if (enabled) "6" else "5"} 05 10 02 42 0B 08 50 10 02 1A 05 02 ${if (enabled) "32" else "00"} 00 00 00"
        bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)
    }

    fun setLoudSoundReduction(enabled: Boolean) {
        val hex = "52 1B 00 0${if (enabled) "1" else "0"}"
        val bytes = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        sendPacket(bytes)
    }
    fun findChangedIndex(oldArray: BooleanArray, newArray: BooleanArray): Int {
        for (i in oldArray.indices) {
            if (oldArray[i] != newArray[i]) {
                return i
            }
        }
        throw IllegalArgumentException("No element has changed")
    }
    fun updateLongPress(oldLongPressArray: BooleanArray, newLongPressArray: BooleanArray, offListeningMode: Boolean) {
        if (oldLongPressArray.contentEquals(newLongPressArray)) {
            return
        }
        val oldOffEnabled = oldLongPressArray[0]
        val oldAncEnabled = oldLongPressArray[1]
        val oldTransparencyEnabled = oldLongPressArray[2]
        val oldAdaptiveEnabled = oldLongPressArray[3]

        val newOffEnabled = newLongPressArray[0]
        val newAncEnabled = newLongPressArray[1]
        val newTransparencyEnabled = newLongPressArray[2]
        val newAdaptiveEnabled = newLongPressArray[3]

        val changedIndex = findChangedIndex(oldLongPressArray, newLongPressArray)
        Log.d("AirPodsService", "changedIndex: $changedIndex")
        var packet: ByteArray? = null
        if (offListeningMode) {
            packet = when (changedIndex) {
                0 -> {
                    if (newOffEnabled) {
                        when {
                            oldAncEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ANC.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_ADAPTIVE_AND_ANC.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    } else {
                        when {
                            oldAncEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_EVERYTHING.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ANC.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_ADAPTIVE_AND_ANC.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    }
                }

                1 -> {
                    if (newAncEnabled) {
                        when {
                            oldOffEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ANC_FROM_OFF_AND_TRANSPARENCY.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_ANC_FROM_OFF_AND_ADAPTIVE.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_ANC_FROM_EVERYTHING.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ANC_FROM_OFF_AND_TRANSPARENCY.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_ANC_FROM_OFF_AND_ADAPTIVE.value
                            oldTransparencyEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_OFF_FROM_TRANSPARENCY_AND_ADAPTIVE.value
                            else -> null
                        }
                    }
                }

                2 -> {
                    if (newTransparencyEnabled) {
                        when {
                            oldOffEnabled && oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_OFF_AND_ADAPTIVE.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.ENABLE_TRANSPARENCY_FROM_ADAPTIVE_AND_ANC.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_OFF_AND_ADAPTIVE.value
                            oldAncEnabled && oldAdaptiveEnabled -> LongPressPackets.DISABLE_TRANSPARENCY_FROM_ADAPTIVE_AND_ANC.value
                            else -> null
                        }
                    }
                }

                3 -> {
                    if (newAdaptiveEnabled) {
                        when {
                            oldOffEnabled && oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_OFF_AND_TRANSPARENCY.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.ENABLE_ADAPTIVE_FROM_TRANSPARENCY_AND_ANC.value
                            else -> null
                        }
                    } else {
                        when {
                            oldOffEnabled && oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_EVERYTHING.value
                            oldOffEnabled && oldAncEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_OFF_AND_ANC.value
                            oldOffEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_OFF_AND_TRANSPARENCY.value
                            oldAncEnabled && oldTransparencyEnabled -> LongPressPackets.DISABLE_ADAPTIVE_FROM_TRANSPARENCY_AND_ANC.value
                            else -> null
                        }
                    }
                }

                else -> null
            }
        } else {
            when (changedIndex) {
                1 -> {
                    packet = if (newLongPressArray[1]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_ANC_OFF_DISABLED.value
                    }
                }
                2 -> {
                    packet = if (newLongPressArray[2]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_TRANSPARENCY_OFF_DISABLED.value
                    }
                }
                3 -> {
                    packet = if (newLongPressArray[3]) {
                        LongPressPackets.ENABLE_EVERYTHING_OFF_DISABLED.value
                    } else {
                        LongPressPackets.DISABLE_ADAPTIVE_OFF_DISABLED.value
                    }
                }
            }

        }
        packet?.let {
            Log.d("AirPodsService", "Sending packet: ${it.joinToString(" ") { "%02X".format(it) }}")
            sendPacket(it)
        }
    }

    override fun onDestroy() {
        clearPacketLogs()
        Log.d("AirPodsService", "Service stopped is being destroyed for some reason!")
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(disconnectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}