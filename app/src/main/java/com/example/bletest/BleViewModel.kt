package com.example.bletest

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.bletest.R
data class HistoryFileEntry(
    val name: String,
    val uri: Uri,
    val path: String,
    val lastModified: Long,
    val sizeBytes: Long
)

private data class DeviceOverview(
    val name: String,
    val devices: List<DeviceSnapshot>,
    val isAdvertising: Boolean,
    val isScanning: Boolean
)

class BleViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val dataStore = appContext.devicePrefsDataStore
    private val controller: BleController by lazy {
        BleEngine.controller(appContext)
    }

    private val defaultDeviceName: String by lazy { buildDefaultDeviceName() }

    private val deviceNameFlow = dataStore.data
        .mapDeviceName(defaultDeviceName)
        .stateIn(viewModelScope, SharingStarted.Eagerly, defaultDeviceName)

    private val errorMessage = MutableStateFlow<String?>(null)
    private val _exportEvents = MutableSharedFlow<HistoryFileEntry>(extraBufferCapacity = 1)
    val exportEvents = _exportEvents.asSharedFlow()
    private val _historyFiles = MutableStateFlow<List<HistoryFileEntry>>(emptyList())
    val historyFiles = _historyFiles.asStateFlow()

    private val advertisingScanning = combine(
        controller.isAdvertising,
        controller.isScanning
    ) { advertising, scanning -> advertising to scanning }

    private val connectionMeta = combine(
        controller.connectedAddress,
        controller.rssiHistory,
        controller.historyRecords
    ) { address, history, records -> Triple(address, history, records) }

    private val bluetoothMeta = combine(
        controller.bluetoothEnabled,
        errorMessage
    ) { enabled, error -> enabled to error }

    private val automationMeta = combine(
        controller.automationState,
        controller.reconnecting
    ) { enabled, reconnecting -> enabled to reconnecting }

    private val deviceOverview = combine(
        deviceNameFlow,
        controller.devices,
        advertisingScanning
    ) { name, devices, advertisingPair ->
        val (advertising, scanning) = advertisingPair
        DeviceOverview(
            name = name,
            devices = devices,
            isAdvertising = advertising,
            isScanning = scanning
        )
    }

    private val baseUiState = deviceOverview
        .combine(connectionMeta) { overview, connectionTriple ->
            val (connectedAddress, rssiHistory, historyRecords) = connectionTriple
            BleUiState(
                deviceName = overview.name,
                isAdvertising = overview.isAdvertising,
                isScanning = overview.isScanning,
                devices = overview.devices,
                connectedDeviceAddress = connectedAddress,
                rssiHistory = rssiHistory,
                historyRecords = historyRecords
            )
        }
        .combine(bluetoothMeta) { partialState, bluetoothPair ->
            val (bluetoothEnabled, error) = bluetoothPair
            partialState.copy(
                bluetoothEnabled = bluetoothEnabled,
                errorMessage = error
            )
        }

    val uiState: StateFlow<BleUiState> = baseUiState
        .combine(automationMeta) { partialState, automationPair ->
            val (automationEnabled, reconnecting) = automationPair
            partialState.copy(
                isAutoReconnectEnabled = automationEnabled,
                isReconnecting = reconnecting
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BleUiState(deviceName = defaultDeviceName))

    init {
        controller.refreshBluetoothState()
        viewModelScope.launch {
            controller.errors.collect { message ->
                errorMessage.value = message
            }
        }
        viewModelScope.launch {
            deviceNameFlow.collect { name ->
                controller.updateAdvertisingInfo(name)
            }
        }
        viewModelScope.launch {
            deviceNameFlow.collect { name ->
                controller.setBluetoothName(name)
            }
        }
        viewModelScope.launch {
            combine(
                controller.isAdvertising,
                controller.isScanning,
                controller.connectedAddress,
                controller.automationState
            ) { advertising, scanning, connectedAddress, automation ->
                advertising || scanning || automation || connectedAddress != null
            }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        BleForegroundService.start(appContext)
                    } else {
                        BleForegroundService.stop(appContext)
                    }
                }
        }
        refreshHistoryFiles()
    }

    fun requiredPermissions(): Array<String> = controller.requiredPermissions()

    fun hasAllPermissions(context: Context): Boolean = controller.hasAllPermissions(context)

    fun acknowledgeMessage() {
        errorMessage.value = null
    }

    fun startAdvertising() {
        controller.startAdvertising(deviceNameFlow.value)
    }

    fun stopAdvertising() {
        controller.stopAdvertising()
    }

    fun startScan() {
        controller.startScan()
    }

    fun stopScan() {
        controller.stopScan()
    }

    fun clearDiscoveredDevices() {
        val success = controller.clearDiscoveredDevices()
        errorMessage.value = if (success) {
            appContext.getString(R.string.devices_cleared)
        } else {
            appContext.getString(R.string.devices_clear_connected)
        }
    }

    fun refreshBluetoothState() {
        controller.refreshBluetoothState()
    }

    fun connect(address: String) {
        controller.connect(address)
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun disconnectAndExport() {
        viewModelScope.launch {
            controller.disconnect()
            delay(DISCONNECT_LOG_SETTLE_MS)
            val log = controller.sessionLogSnapshot()
            if (log.isEmpty()) {
                errorMessage.value = appContext.getString(R.string.export_no_data)
                return@launch
            }
            val entry = exportHistoryToCsv(log)
            if (entry != null) {
                _exportEvents.emit(entry)
                refreshHistoryFiles()
            } else {
                errorMessage.value = appContext.getString(R.string.export_failed)
            }
        }
    }

    fun updateDeviceName(newName: String) {
        val trimmed = newName.trim().takeIf { it.isNotEmpty() } ?: return
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[DevicePreferencesKeys.DEVICE_NAME] = trimmed
            }
        }
        val wasAdvertising = controller.isAdvertising.value
        if (wasAdvertising) {
            controller.stopAdvertising()
        }
        controller.setBluetoothName(trimmed)
        controller.updateAdvertisingInfo(trimmed)
        if (wasAdvertising) {
            controller.startAdvertising(trimmed)
        }
        errorMessage.value = appContext.getString(R.string.name_saved)
    }

    fun refreshHistoryFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val exportsDir = File(appContext.getExternalFilesDir(null), "exports")
            if (!exportsDir.exists()) {
                exportsDir.mkdirs()
            }
            val authority = "${appContext.packageName}.fileprovider"
            val files = exportsDir
                .listFiles { file -> file.extension.equals("csv", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            val entries = files.mapNotNull { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(appContext, authority, file)
                    HistoryFileEntry(
                        name = file.name,
                        uri = uri,
                        path = file.absolutePath,
                        lastModified = file.lastModified(),
                        sizeBytes = file.length()
                    )
                }.getOrNull()
            }
            _historyFiles.emit(entries)
        }
    }

    fun shareHistoryFile(entry: HistoryFileEntry) {
        viewModelScope.launch {
            _exportEvents.emit(entry)
        }
    }

    private fun buildDefaultDeviceName(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        val suffix = androidId?.takeLast(4)?.uppercase() ?: "0000"
        val model = Build.MODEL?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), "")?.let {
            it.take(4).uppercase()
        } ?: "CAR"
        return "CAR$suffix$model"
    }

    private suspend fun exportHistoryToCsv(entries: List<SessionLogEntry>): HistoryFileEntry? = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext null
        runCatching {
            val exportsDir = File(appContext.getExternalFilesDir(null), "exports")
            if (!exportsDir.exists()) {
                exportsDir.mkdirs()
            }
            val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
                .format(Instant.now().atZone(ZoneId.systemDefault()))
            val file = File(exportsDir, "ble_session_$timestamp.csv")
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            file.bufferedWriter().use { writer ->
                writer.appendLine("time,type,value,name")
                entries.forEach { entry ->
                    val time = Instant.ofEpochMilli(entry.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(formatter)
                    val typeValue = when (entry.type) {
                        SessionLogEntryType.STATUS -> "status"
                        SessionLogEntryType.RSSI_SAMPLE -> "rssi"
                    }
                    val payload = entry.payload.replace("\"", "\"\"")
                    val name = entry.deviceName?.replace("\"", "\"\"") ?: ""
                    writer.appendLine("$time,$typeValue,\"$payload\",\"$name\"")
                }
            }
            val authority = "${appContext.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(appContext, authority, file)
            HistoryFileEntry(
                name = file.name,
                uri = uri,
                path = file.absolutePath,
                lastModified = file.lastModified(),
                sizeBytes = file.length()
            )
        }.getOrNull()
    }

    companion object {
        private const val DISCONNECT_LOG_SETTLE_MS = 350L
    }
}
