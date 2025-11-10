package com.example.bletest

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bletest.ui.theme.BleTestTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: BleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BleTestTheme {
                val context = LocalContext.current
                val snackbarHostState = remember { SnackbarHostState() }
                val uiState by viewModel.uiState.collectAsState()
                val historyFiles by viewModel.historyFiles.collectAsState()

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.refreshBluetoothState()
                }

                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    viewModel.refreshBluetoothState()
                }

                LaunchedEffect(Unit) {
                    if (!viewModel.hasAllPermissions(context)) {
                        permissionLauncher.launch(viewModel.requiredPermissions())
                    }
                }

                LaunchedEffect(uiState.errorMessage) {
                    val message = uiState.errorMessage
                    if (!message.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(
                            message = message,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                        viewModel.acknowledgeMessage()
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.exportEvents.collect { entry ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, entry.uri)
                            putExtra(Intent.EXTRA_SUBJECT, entry.name)
                            putExtra(Intent.EXTRA_TITLE, entry.name)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(
                                context.contentResolver,
                                entry.name,
                                entry.uri
                            )
                        }
                        val resolvedActivities = context.packageManager
                            .queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        resolvedActivities.forEach { resolveInfo ->
                            context.grantUriPermission(
                                resolveInfo.activityInfo.packageName,
                                entry.uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        val chooser = Intent.createChooser(
                            shareIntent,
                            context.getString(R.string.share_rssi_history)
                        ).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(chooser)
                        } catch (ex: ActivityNotFoundException) {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.share_not_available)
                            )
                        }
                    }
                }

                BleScreen(
                    state = uiState,
                    historyFiles = historyFiles,
                    snackbarHostState = snackbarHostState,
                    onRename = viewModel::updateDeviceName,
                    onToggleAdvertising = { shouldAdvertise ->
                        if (shouldAdvertise) viewModel.startAdvertising() else viewModel.stopAdvertising()
                    },
                    onToggleScanning = { shouldScan ->
                        if (shouldScan) viewModel.startScan() else viewModel.stopScan()
                    },
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnectAndExport,
                    onRequestPermissions = {
                        permissionLauncher.launch(viewModel.requiredPermissions())
                    },
                    onEnableBluetooth = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    onShareHistory = viewModel::shareHistoryFile,
                    onRefreshHistory = viewModel::refreshHistoryFiles,
                    onClearDevices = viewModel::clearDiscoveredDevices
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScreen(
    state: BleUiState,
    historyFiles: List<HistoryFileEntry>,
    snackbarHostState: SnackbarHostState,
    onRename: (String) -> Unit,
    onToggleAdvertising: (Boolean) -> Unit,
    onToggleScanning: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onShareHistory: (HistoryFileEntry) -> Unit,
    onRefreshHistory: () -> Unit,
    onClearDevices: () -> Unit
) {
    var showHistoryDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "BLE Companion", fontSize = 20.sp) },
                actions = {
                    IconButton(
                        onClick = {
                            onRefreshHistory()
                            showHistoryDialog = true
                        }
                    ) {
                        Icon(imageVector = Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onRequestPermissions) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Permissions")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        val connectedDevice = state.devices.firstOrNull { it.address == state.connectedDeviceAddress }
        val bluetoothDisabled = !state.bluetoothEnabled
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                DeviceIdentityCard(
                    state = state,
                    onRename = onRename,
                    onToggleAdvertising = onToggleAdvertising,
                    onToggleScanning = onToggleScanning,
                    onClearDevices = onClearDevices
                )
            }
            if (bluetoothDisabled) {
                item {
                    WarningCard(
                        title = "Bluetooth is off",
                        message = "Turn on Bluetooth to advertise and scan for nearby devices.",
                        actionLabel = "Enable",
                        onAction = onEnableBluetooth
                    )
                }
            }
            if (connectedDevice != null && state.rssiHistory.isNotEmpty()) {
                item {
                    SignalHistoryCard(device = connectedDevice, samples = state.rssiHistory)
                }
            }
            if (state.historyRecords.isNotEmpty()) {
                item {
                    HistoryTimelineCard(records = state.historyRecords)
                }
            }
            item { SectionHeader(title = "Nearby devices") }
            if (state.devices.isEmpty()) {
                item { EmptyStateCard(onScan = { onToggleScanning(true) }) }
            } else {
                items(state.devices, key = { it.address }) { device ->
                    DeviceCard(
                        device = device,
                        isConnected = device.address == state.connectedDeviceAddress,
                        onConnect = { onConnect(device.address) },
                        onDisconnect = onDisconnect
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(48.dp)) }
        }
        if (showHistoryDialog) {
            HistoryDialog(
                entries = historyFiles,
                onDismiss = { showHistoryDialog = false },
                onShare = { onShareHistory(it) }
            )
        }
    }
}

@Composable
fun DeviceIdentityCard(
    state: BleUiState,
    onRename: (String) -> Unit,
    onToggleAdvertising: (Boolean) -> Unit,
    onToggleScanning: (Boolean) -> Unit,
    onClearDevices: () -> Unit
) {
    var pendingName by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    val nameChanged = pendingName.trim() != state.deviceName
    val clearEnabled = state.connectedDeviceAddress == null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Local device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedTextField(
                value = pendingName,
                onValueChange = {
                    if (it.length <= 20) pendingName = it
                },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onRename(pendingName) },
                    enabled = nameChanged
                ) {
                    Text(text = stringResource(R.string.device_name_save))
                }
                OutlinedButton(
                    onClick = onClearDevices,
                    enabled = clearEnabled
                ) {
                    Text(text = stringResource(R.string.device_list_clear))
                }
            }
            if (!clearEnabled) {
                Text(
                    text = stringResource(R.string.clear_devices_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Advertising")
                }
                Switch(
                    checked = state.isAdvertising,
                    onCheckedChange = onToggleAdvertising,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.primary)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning")
                }
                Switch(
                    checked = state.isScanning,
                    onCheckedChange = onToggleScanning,
                    colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.secondary)
                )
            }
            Text(
                text = "Devices found: ${state.devices.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.isReconnecting) {
                Text(
                    text = stringResource(R.string.auto_reconnecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else if (state.isAutoReconnectEnabled) {
                Text(
                    text = stringResource(R.string.auto_reconnect_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceSnapshot,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = device.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isConnected) {
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(text = "Low Energy - LE", style = MaterialTheme.typography.bodySmall)
            Text(
                text = buildSignalString(device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildQualityString(device.signalQualityPercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = device.estimatedDistanceMeters?.let { "≈ ${formatDistance(it)}" } ?: "Distance unknown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val bondText = when {
                device.isConnected && device.isBonded -> "Bonded - Connected"
                device.isConnected -> "Connected"
                device.isBonded -> "Bonded"
                else -> "Not bonded"
            }
            Text(
                text = bondText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isConnected) {
                    FilledTonalButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
fun WarningCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun EmptyStateCard(onScan: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(text = "No nearby devices", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Start advertising on another phone and tap Scan to discover it.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Button(onClick = onScan) { Text("Scan now") }
        }
    }
}

@Composable
fun SignalHistoryCard(device: DeviceSnapshot, samples: List<RssiSample>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "${device.displayName} signal", style = MaterialTheme.typography.titleMedium)
            RssiLineChart(
                samples = samples,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )
        }
    }
}

@Composable
fun RssiLineChart(samples: List<RssiSample>, modifier: Modifier = Modifier) {
    if (samples.isEmpty()) {
        Text("Signal history will appear after connecting.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val min = (samples.minOf { it.rssi } - 5).coerceAtMost(-30)
    val max = (samples.maxOf { it.rssi } + 5).coerceAtLeast(-100)
    val range = (max - min).takeIf { it != 0 } ?: 1
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    Canvas(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        val linePath = Path()
        samples.forEachIndexed { index, sample ->
            val x = if (samples.size == 1) size.width else index / (samples.size - 1f) * size.width
            val norm = (sample.rssi - min).toFloat() / range.toFloat()
            val y = size.height - norm * size.height
            if (index == 0) {
                linePath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
            }
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
        )
        samples.forEachIndexed { index, sample ->
            val x = if (samples.size == 1) size.width else index / (samples.size - 1f) * size.width
            val norm = (sample.rssi - min).toFloat() / range.toFloat()
            val y = size.height - norm * size.height
            drawCircle(
                color = lineColor,
                radius = 6f,
                center = Offset(x, y)
            )
        }
        val gridLevels = listOf(-40, -60, -80, -100)
        gridLevels.forEach { level ->
            val ratio = (level - min).toFloat() / range.toFloat()
            val y = size.height - ratio * size.height
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
        }
    }
}

@Composable
fun HistoryDialog(
    entries: List<HistoryFileEntry>,
    onDismiss: () -> Unit,
    onShare: (HistoryFileEntry) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.history_title)) },
        text = {
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(entries, key = { it.path }) { entry ->
                        HistoryEntryRow(entry = entry, onShare = onShare)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.history_close))
            }
        }
    )
}

@Composable
private fun HistoryEntryRow(
    entry: HistoryFileEntry,
    onShare: (HistoryFileEntry) -> Unit
) {
    val context = LocalContext.current
    val sizeLabel = remember(entry.sizeBytes) {
        Formatter.formatShortFileSize(context, entry.sizeBytes)
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedDate = remember(entry.lastModified) {
        Instant.ofEpochMilli(entry.lastModified)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = entry.name, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.history_last_modified, formattedDate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.history_size, sizeLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.history_path, entry.path),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onShare(entry) }) {
                Text(text = stringResource(R.string.history_share))
            }
        }
    }
}

@Composable
fun HistoryTimelineCard(records: List<HistoryRecord>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = stringResource(R.string.history_events_title), style = MaterialTheme.typography.titleMedium)
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_events_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()) }
                records.takeLast(20).asReversed().forEach { record ->
                    val time = Instant.ofEpochMilli(record.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .format(formatter)
                    val label = if (!record.deviceName.isNullOrBlank()) {
                        "${record.value} (${record.deviceName})"
                    } else {
                        record.value
                    }
                    Text(
                        text = "$time • $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun buildSignalString(device: DeviceSnapshot): String {
    val rssi = device.rssi?.let { "$it dBm" } ?: "--"
    val percent = device.signalQualityPercent?.let { " (${it}%)" } ?: ""
    return rssi + percent
}

private fun buildQualityString(percent: Int?): String = when {
    percent == null -> "Signal unknown"
    percent >= 80 -> "Excellent"
    percent >= 60 -> "Good"
    percent >= 40 -> "Fair"
    else -> "Weak"
}

private fun formatDistance(distance: Double): String {
    return if (distance < 1) {
        "${(distance * 100).roundToInt() / 100.0} m"
    } else {
        "${distance.roundToInt()} m"
    }
}
