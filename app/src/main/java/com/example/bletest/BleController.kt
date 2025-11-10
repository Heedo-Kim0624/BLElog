@file:Suppress("MissingPermission")

package com.example.bletest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.LinkedHashMap
import kotlin.math.pow

private val SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
private val INFO_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")

data class DeviceSnapshot(
    val address: String,
    val displayName: String,
    val rawName: String?,
    val rssi: Int?,
    val signalQualityPercent: Int?,
    val estimatedDistanceMeters: Double?,
    val isBonded: Boolean,
    val isConnected: Boolean,
    val lastSeenTimestamp: Long,
    val advertiseName: String?
)

data class RssiSample(
    val timestampMillis: Long,
    val rssi: Int
)

data class BleUiState(
    val deviceName: String = "",
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val devices: List<DeviceSnapshot> = emptyList(),
    val connectedDeviceAddress: String? = null,
    val rssiHistory: List<RssiSample> = emptyList(),
    val historyRecords: List<HistoryRecord> = emptyList(),
    val errorMessage: String? = null,
    val isAutoReconnectEnabled: Boolean = false,
    val isReconnecting: Boolean = false
)

data class HistoryRecord(
    val timestampMillis: Long,
    val value: String,
    val deviceName: String?
)

enum class SessionLogEntryType {
    STATUS,
    RSSI_SAMPLE
}

data class SessionLogEntry(
    val timestampMillis: Long,
    val type: SessionLogEntryType,
    val payload: String,
    val deviceName: String?
)

class BleController(
    context: Context,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val advertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private val devicesCache = LinkedHashMap<String, CachedDevice>()

    private val _devices = MutableStateFlow<List<DeviceSnapshot>>(emptyList())
    val devices: StateFlow<List<DeviceSnapshot>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress.asStateFlow()

    private val _rssiHistory = MutableStateFlow<List<RssiSample>>(emptyList())
    val rssiHistory: StateFlow<List<RssiSample>> = _rssiHistory.asStateFlow()

    private val _historyRecords = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val historyRecords: StateFlow<List<HistoryRecord>> = _historyRecords.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors = _errors.asSharedFlow()

    private val _bluetoothEnabled =
        MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _automationState = MutableStateFlow(false)
    val automationState: StateFlow<Boolean> = _automationState.asStateFlow()

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private var advertiseCallback: AdvertiseCallback? = null
    private var lastAdvertiseName: String? = null
    private var gattServer: BluetoothGattServer? = null
    private var infoCharacteristic: BluetoothGattCharacteristic? = null
    private var currentGatt: BluetoothGatt? = null
    private var autoReconnectDevice: BluetoothDevice? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var manualDisconnect = false
    private var currentDeviceName: String? = null
    private val logLock = Any()
    private val sessionLog = mutableListOf<SessionLogEntry>()
    private var sessionActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rssiReader = object : Runnable {
        override fun run() {
            val gatt = currentGatt
            if (gatt != null && _connectedAddress.value != null) {
                gatt.readRemoteRssi()
                mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    private fun deviceIdentifier(result: ScanResult): String? {
        val address = result.device?.address?.takeIf { it.isNullOrBlank().not() }
        return address ?: readAdvertisedName(result)?.let { "name:$it" }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state =
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                if (state != BluetoothAdapter.STATE_ON) {
                    stopAdvertising()
                    stopScan()
                    closeGatt()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null) {
                addOrUpdateDevice(result)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addOrUpdateDevice(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _errors.tryEmit("BLE scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val address = gatt?.device?.address
                    _connectedAddress.value = address
                    gatt?.discoverServices()
                    mainHandler.post(rssiReader)
                    autoReconnectDevice = gatt?.device
                    currentDeviceName = gatt?.device?.name
                        ?: address?.let { devicesCache[it]?.displayName }
                        ?: address
                    val wasReconnecting = _reconnecting.value
                    cancelReconnect()
                    _automationState.value = true
                    appendHistory(
                        if (wasReconnecting) "RECONNECTED $address" else "CONNECTED $address",
                        currentDeviceName
                    )
                    scope.launch {
                        _errors.emit("Connected to ${currentDeviceName ?: address}")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    scope.launch {
                        _errors.emit("Disconnected (${status})")
                    }
                    mainHandler.removeCallbacks(rssiReader)
                    _connectedAddress.value = null
                    if (manualDisconnect) {
                        cancelReconnect()
                        autoReconnectDevice = null
                        _automationState.value = false
                        appendHistory("MANUAL_DISCONNECTED ${gatt?.device?.address ?: "unknown"}", currentDeviceName)
                        currentDeviceName = null
                    } else {
                        appendHistory("REMOTE_DISCONNECTED (status=$status)", currentDeviceName)
                        autoReconnectDevice = gatt?.device ?: autoReconnectDevice
                        if (_automationState.value) {
                            scheduleReconnect()
                        } else {
                            cancelReconnect()
                            currentDeviceName = null
                        }
                    }
                    closeGatt()
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateRssi(gatt.device.address, rssi)
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun hasAllPermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun refreshBluetoothState() {
        _bluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun setBluetoothName(newName: String) {
        try {
            if (!newName.isNullOrBlank() && bluetoothAdapter?.name != newName) {
                bluetoothAdapter?.name = newName
            }
        } catch (sec: SecurityException) {
            _errors.tryEmit("Unable to set adapter name. Grant BLUETOOTH_CONNECT.")
        }
    }

    fun startAdvertising(advertiseName: String) {
        if (_isAdvertising.value) return
        if (advertiser == null) {
            _errors.tryEmit("BLE advertiser unavailable on this device.")
            return
        }
        lastAdvertiseName = advertiseName
        ensureGattServer(advertiseName)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val serviceBytes = advertiseName
            .toByteArray(StandardCharsets.UTF_8)
            .let { if (it.size > MAX_SERVICE_DATA_BYTES) it.copyOf(MAX_SERVICE_DATA_BYTES) else it }

        fun buildAdvertiseData(includeServiceData: Boolean): AdvertiseData {
            val builder = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
            if (includeServiceData && serviceBytes.isNotEmpty()) {
                builder.addServiceData(ParcelUuid(SERVICE_UUID), serviceBytes)
            }
            return builder.build()
        }

        val primaryData = buildAdvertiseData(includeServiceData = serviceBytes.isNotEmpty())
        val fallbackData = buildAdvertiseData(includeServiceData = false)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .build()

        var attemptedFallback = false

        fun startAdvertisingInternal(includeServiceData: Boolean) {
            val data = if (includeServiceData) primaryData else fallbackData
            advertiser.startAdvertising(
                settings,
                data,
                scanResponse,
                advertiseCallback
            )
        }

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                _isAdvertising.value = true
                appendHistory("ADVERTISING_STARTED \"$advertiseName\"", deviceName = null)
            }

            override fun onStartFailure(errorCode: Int) {
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && !attemptedFallback && serviceBytes.isNotEmpty()) {
                    attemptedFallback = true
                    appendHistory("ADVERTISING_RETRY_WITHOUT_NAME", deviceName = null)
                    startAdvertisingInternal(includeServiceData = false)
                    return
                }
                val description = advertiseErrorDescription(errorCode)
                _errors.tryEmit("Advertise start failed: $description")
                _isAdvertising.value = false
                appendHistory("ADVERTISING_FAILED $description", deviceName = null)
            }
        }

        try {
            startAdvertisingInternal(includeServiceData = serviceBytes.isNotEmpty())
        } catch (sec: SecurityException) {
            _errors.tryEmit("Missing permission for advertising.")
            appendHistory("ADVERTISING_FAILED Missing permission", deviceName = null)
        }
    }

    fun stopAdvertising() {
        val callback = advertiseCallback ?: return
        try {
            advertiser?.stopAdvertising(callback)
        } catch (_: SecurityException) {
        }
        advertiseCallback = null
        _isAdvertising.value = false
        closeGattServer()
        appendHistory("ADVERTISING_STOPPED", deviceName = null)
    }

    fun startScan() {
        if (_isScanning.value) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _errors.tryEmit("BLE scanner unavailable.")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            _isScanning.value = true
            appendHistory("SCAN_STARTED", deviceName = null)
        } catch (sec: SecurityException) {
            _errors.tryEmit("Missing permission for scanning.")
            appendHistory("SCAN_FAILED Missing permission", deviceName = null)
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
        _isScanning.value = false
        appendHistory("SCAN_STOPPED", deviceName = null)
    }

    fun clearDiscoveredDevices(): Boolean {
        if (_connectedAddress.value != null) {
            appendHistory("CLEAR_DEVICES_SKIPPED_CONNECTED", currentDeviceName)
            return false
        }
        if (devicesCache.isEmpty()) {
            appendHistory("CLEAR_DEVICES_EMPTY", currentDeviceName)
            return true
        }
        devicesCache.clear()
        _devices.value = emptyList()
        appendHistory("CLEAR_DEVICES", currentDeviceName)
        return true
    }

    fun connect(address: String) {
        val device = devicesCache[address]?.device
        if (device == null) {
            _errors.tryEmit("Device $address not found")
            return
        }

        try {
            if (!sessionActive) {
                beginSession(device)
            }
            manualDisconnect = false
            _automationState.value = true
            autoReconnectDevice = device
            currentDeviceName = device.name ?: devicesCache[address]?.displayName ?: address
            cancelReconnect()
            appendHistory("CONNECT_REQUEST $address", currentDeviceName)
            closeGatt()
            _connectedAddress.value = null
            currentGatt = device.connectGatt(appContext, false, gattCallback)
            appendHistory("CONNECTING $address", currentDeviceName)
        } catch (sec: SecurityException) {
            _errors.tryEmit("Missing permission to connect.")
            appendHistory("CONNECT_FAILED Missing permission", currentDeviceName)
        }
    }

    fun disconnect() {
        manualDisconnect = true
        appendHistory("MANUAL_DISCONNECT_REQUESTED", currentDeviceName)
        _automationState.value = false
        cancelReconnect()
        autoReconnectDevice = null
        _connectedAddress.value = null
        mainHandler.removeCallbacks(rssiReader)
        try {
            currentGatt?.disconnect()
        } catch (_: SecurityException) {
        }
        closeGatt()
        endSession()
        appendHistory("MANUAL_DISCONNECT_COMPLETED", currentDeviceName)
        currentDeviceName = null
    }

    fun updateAdvertisingInfo(name: String) {
        infoCharacteristic?.value = name.toByteArray(StandardCharsets.UTF_8)
    }

    fun clear() {
        stopScan()
        stopAdvertising()
        closeGatt()
        mainHandler.removeCallbacks(rssiReader)
        cancelReconnect()
        autoReconnectDevice = null
        _automationState.value = false
        sessionActive = false
        synchronized(logLock) { sessionLog.clear() }
        _historyRecords.value = emptyList()
        currentDeviceName = null
        advertiser?.let {
            // nothing to close
        }
        runCatching {
            appContext.unregisterReceiver(bluetoothStateReceiver)
        }
    }

    private fun ensureGattServer(advertiseName: String) {
        if (gattServer != null) {
            updateAdvertisingInfo(advertiseName)
            return
        }
        val server = bluetoothManager?.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            _errors.tryEmit("Unable to open GATT server.")
            return
        }
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val infoChar = BluetoothGattCharacteristic(
            INFO_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        infoChar.value = advertiseName.toByteArray(StandardCharsets.UTF_8)
        service.addCharacteristic(infoChar)
        server.addService(service)
        gattServer = server
        infoCharacteristic = infoChar
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            val address = device.address
            val cached = devicesCache[address]
            if (cached != null) {
                devicesCache[address] = cached.copy(isConnected = newState == BluetoothProfile.STATE_CONNECTED)
                publishDevicesSnapshot()
            } else {
                val identifier = address ?: "server:${device.hashCode()}"
                devicesCache[identifier] = CachedDevice(
                    identifier = identifier,
                    device = device,
                    displayName = device.name ?: identifier,
                    advertiseName = null,
                    rssi = null,
                    lastSeen = System.currentTimeMillis(),
                    isConnected = newState == BluetoothProfile.STATE_CONNECTED
                )
                publishDevicesSnapshot()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid == INFO_CHARACTERISTIC_UUID) {
                val value = infoCharacteristic?.value ?: ByteArray(0)
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    private fun closeGatt() {
        try {
            currentGatt?.close()
        } catch (_: SecurityException) {
        } finally {
            currentGatt = null
        }
    }

    private fun closeGattServer() {
        gattServer?.close()
        gattServer = null
        infoCharacteristic = null
    }

    private fun addOrUpdateDevice(result: ScanResult) {
        val identifier = deviceIdentifier(result) ?: return
        val device = result.device
        val advertiseName = readAdvertisedName(result)
        val entry = CachedDevice(
            identifier = identifier,
            device = device,
            displayName = parseDisplayName(result),
            advertiseName = advertiseName,
            rssi = result.rssi,
            lastSeen = System.currentTimeMillis(),
            isConnected = _connectedAddress.value == device?.address || device?.isConnected(bluetoothManager) == true
        )
        devicesCache[identifier] = entry
        publishDevicesSnapshot()
    }

    private fun updateRssi(address: String, rssi: Int) {
        val key = devicesCache.entries.firstOrNull { it.value.device?.address == address }?.key ?: address
        val existing = devicesCache[key]
        if (existing != null) {
            devicesCache[key] = existing.copy(
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                isConnected = true
            )
        } else {
            val remote = kotlin.runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            devicesCache[key] = CachedDevice(
                identifier = key,
                device = remote,
                displayName = remote?.name ?: address,
                advertiseName = remote?.name,
                rssi = rssi,
                lastSeen = System.currentTimeMillis(),
                isConnected = true
            )
        }
        appendRssiSample(rssi)
        publishDevicesSnapshot()
    }

    private fun appendRssiSample(rssi: Int) {
        val now = System.currentTimeMillis()
        _rssiHistory.update { current ->
            (current + RssiSample(now, rssi))
                .filter { now - it.timestampMillis <= RSSI_HISTORY_WINDOW_MS }
        }
        if (sessionActive) {
            synchronized(logLock) {
                sessionLog += SessionLogEntry(
                    timestampMillis = now,
                    type = SessionLogEntryType.RSSI_SAMPLE,
                    payload = rssi.toString(),
                    deviceName = currentDeviceName
                )
            }
        }
    }

    private fun appendHistory(value: String, deviceName: String? = currentDeviceName) {
        val now = System.currentTimeMillis()
        _historyRecords.update { current ->
            (current + HistoryRecord(timestampMillis = now, value = value, deviceName = deviceName))
                .takeLast(MAX_HISTORY_RECORDS)
        }
        synchronized(logLock) {
            sessionLog += SessionLogEntry(
                timestampMillis = now,
                type = SessionLogEntryType.STATUS,
                payload = value,
                deviceName = deviceName
            )
        }
    }

    private fun beginSession(device: BluetoothDevice) {
        synchronized(logLock) {
            sessionLog.clear()
            sessionActive = true
        }
        currentDeviceName = device.name ?: device.address
        _historyRecords.value = emptyList()
        appendHistory("SESSION_START ${device.address}", currentDeviceName)
    }

    private fun endSession() {
        sessionActive = false
    }

    fun sessionLogSnapshot(): List<SessionLogEntry> =
        synchronized(logLock) { sessionLog.toList() }

    private fun publishDevicesSnapshot() {
        val snapshots = devicesCache.values.map { cached ->
            val rssi = cached.rssi
            DeviceSnapshot(
                address = cached.identifier,
                displayName = cached.displayName,
                rawName = cached.device?.name,
                rssi = rssi,
                signalQualityPercent = rssi?.let { it.toSignalPercent() },
                estimatedDistanceMeters = rssi?.let { it.toDistanceMeters() },
                isBonded = cached.device?.bondState == BluetoothDevice.BOND_BONDED,
                isConnected = cached.isConnected || (_connectedAddress.value == cached.device?.address),
                lastSeenTimestamp = cached.lastSeen,
                advertiseName = cached.advertiseName
            )
        }
        _devices.value = snapshots
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnecting.value = false
    }

    private fun scheduleReconnect() {
        val device = autoReconnectDevice ?: return
        cancelReconnect()
        _reconnecting.value = true
        appendHistory("RECONNECT_SCHEDULED ${device.address}", currentDeviceName)
        reconnectJob = scope.launch {
            delay(RECONNECT_INTERVAL_MS)
            if (manualDisconnect || !_automationState.value) {
                _reconnecting.value = false
                return@launch
            }
            appendHistory("RECONNECT_ATTEMPT ${device.address}", currentDeviceName)
            try {
                currentGatt = device.connectGatt(appContext, false, gattCallback)
            } catch (sec: SecurityException) {
                _errors.tryEmit("Missing permission to reconnect.")
                appendHistory("RECONNECT_FAILED_PERMISSION", currentDeviceName)
                _reconnecting.value = false
            }
        }
    }

    private fun readAdvertisedName(result: ScanResult): String? {
        val data = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return null
        return runCatching { String(data, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun parseDisplayName(result: ScanResult): String {
        val fromData = readAdvertisedName(result)
        if (!fromData.isNullOrBlank()) return fromData
        val record = result.scanRecord
        val deviceName = record?.deviceName ?: result.device?.name
        return if (!deviceName.isNullOrBlank()) deviceName else result.device?.address ?: "Unknown"
    }

    private fun BluetoothDevice?.isConnected(manager: BluetoothManager?): Boolean {
        this ?: return false
        manager ?: return false
        return manager.getConnectedDevices(BluetoothProfile.GATT).any { it.address == address }
    }

    private fun Int.toSignalPercent(): Int {
        val min = -100.0
        val max = -40.0
        val percent = ((this - min) / (max - min) * 100).toInt()
        return percent.coerceIn(0, 100)
    }

    private fun Int.toDistanceMeters(txPower: Int = -59, attenuation: Double = 2.0): Double {
        val ratio = (txPower - this) / (10.0 * attenuation)
        return 10.0.pow(ratio)
    }

    private fun advertiseErrorDescription(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large (error 1)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers (error 2)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started (error 3)"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error (error 4)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported (error 5)"
        else -> "unknown error ($code)"
    }

    private data class CachedDevice(
        val identifier: String,
        val device: BluetoothDevice?,
        val displayName: String,
        val advertiseName: String?,
        val rssi: Int?,
        val lastSeen: Long,
        val isConnected: Boolean
    )

    companion object {
        private const val RSSI_POLL_INTERVAL_MS = 2000L
        private const val RSSI_HISTORY_WINDOW_MS = 60_000L
        private const val RECONNECT_INTERVAL_MS = 5_000L
        private const val MAX_HISTORY_RECORDS = 128
        private const val MAX_SERVICE_DATA_BYTES = 12
    }
}
