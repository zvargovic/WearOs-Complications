package com.example.complicationprovider

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.example.complicationprovider.ui.AppTheme
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private const val TAG = "Main"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }

        // pokreni periodički dohvat (svakih 2 min; interval se mijenja u GoldFetcher-u)
        com.example.complicationprovider.data.GoldFetcher.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        // zaustavi petlju
        com.example.complicationprovider.data.GoldFetcher.stop()
    }
}

private enum class Screen { HOME, SETUP, ALERTS, EDIT_ALERT }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.HOME) }

    var editIndex by remember { mutableStateOf(-1) }
    var editText  by remember { mutableStateOf("") }

    when (screen) {
        Screen.HOME   -> HomeScreen(onOpenSetup = { screen = Screen.SETUP })
        Screen.SETUP  -> SetupScreen(
            onBack = { screen = Screen.HOME },
            onOpenAlerts = { screen = Screen.ALERTS },
            onSaved = { screen = Screen.HOME }
        )
        Screen.ALERTS -> AlertsScreen(
            onBack = { screen = Screen.SETUP },
            onEdit = { idx, text ->
                editIndex = idx
                editText  = text
                screen = Screen.EDIT_ALERT
            }
        )
        Screen.EDIT_ALERT -> EditAlertScreen(
            index = editIndex,
            initialText = editText,
            onBack = { screen = Screen.ALERTS },
            onSaved = { screen = Screen.ALERTS }
        )
    }
}

/* ------------ HOME (start) SCREEN ------------ */
@Composable
private fun HomeScreen(onOpenSetup: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.title),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.subtitle),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        Log.d(TAG, "Open Setup tapped")
                        onOpenSetup()
                    },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Setup",
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/* ------------ SETUP SCREEN ------------ */
@Composable
private fun SetupScreen(
    onBack: () -> Unit,
    onOpenAlerts: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepo(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf("") }
    var alarmOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = repo.flow.first()
        apiKey = current.apiKey
        alarmOn = current.alarmOn
        Log.d(TAG, "Loaded Settings: apiKey='$apiKey', alarmOn=$alarmOn")
    }

    val listState = rememberScalingLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 0),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    text = "Setup",
                    color = MaterialTheme.colors.primary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    textAlign = TextAlign.Center
                )
            }

            item {
                Text(
                    text = "API Key",
                    color = MaterialTheme.colors.onBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            item {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(shape)
                        .background(MaterialTheme.colors.surface)
                        .clickable {
                            Log.d(TAG, "API field clicked -> request focus + show keyboard")
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            Log.d(TAG, "API key changed: '$apiKey'")
                        },
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
                        textStyle = TextStyle(
                            color = MaterialTheme.colors.onBackground,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (apiKey.isEmpty()) {
                                Text(
                                    text = "Enter API key",
                                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            item {
                ToggleChip(
                    checked = alarmOn,
                    onCheckedChange = {
                        alarmOn = it
                        Log.d(TAG, "Alarm toggled: $alarmOn")
                    },
                    label = { Text(if (alarmOn) "Alarm ON" else "Alarm OFF") },
                    toggleControl = { Switch(checked = alarmOn, onCheckedChange = null) }
                )
            }

            item {
                Spacer(Modifier.height(6.dp))
                Chip(
                    onClick = {
                        Log.d(TAG, "Alerts clicked")
                        onOpenAlerts()
                    },
                    label = {
                        Text(
                            text = "Alerts",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactChip(
                        onClick = {
                            Log.d(TAG, "Back tapped (Setup)")
                            onBack()
                        },
                        label = {
                            Text(
                                text = "Back",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = {
                            Log.d(TAG, "Save tapped (apiKey='$apiKey', alarmOn=$alarmOn)")
                            scope.launch {
                                repo.save(apiKey = apiKey, alarmOn = alarmOn)
                                Log.d(TAG, "Settings saved")
                                onSaved()
                            }
                        },
                        label = {
                            Text(
                                text = "Save",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/* ------------ ALERTS SCREEN ------------ */
@Composable
private fun AlertsScreen(
    onBack: () -> Unit,
    onEdit: (index: Int, text: String) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepo(context) }
    val scope = rememberCoroutineScope()

    var alerts by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        alerts = repo.alertsFlow.first()
        Log.d(TAG, "Loaded Alerts: $alerts")
    }

    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 84.dp),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                autoCentering = AutoCenteringParams(itemIndex = 0)
            ) {
                item {
                    Text(
                        text = "Alerts",
                        color = MaterialTheme.colors.primary,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        textAlign = TextAlign.Center
                    )
                }

                if (alerts.isEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No alerts — add one with +",
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    }
                } else {
                    items(alerts.size) { index ->
                        val original = alerts[index]
                        val display = formatAmountLabel(original)

                        AlertRowCompact(
                            displayText = display,
                            onClick = { onEdit(index, original) },
                            onDelete = {
                                Log.d(TAG, "Delete alert: '$original'")
                                val updated = alerts.toMutableList().also { it.removeAt(index) }
                                alerts = updated
                                scope.launch { repo.saveAlerts(updated) }
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // Back – centriran dolje, kompaktan
            CompactChip(
                onClick = {
                    Log.d(TAG, "Back tapped (Alerts)")
                    onBack()
                },
                label = {
                    Text(
                        text = "Back",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f)
            )

            // FAB “+” – DESNO SREDINA (manji: 40dp)
            Button(
                onClick = {
                    val newLabel = "Alert #${alerts.size + 1}"
                    val updated = alerts + newLabel
                    alerts = updated
                    Log.d(TAG, "Added alert: '$newLabel'")
                    scope.launch { repo.saveAlerts(updated) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = MaterialTheme.colors.onBackground,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Kompaktni redak: centrirana širina, mali razmak do kante, kanta 24dp / ikona 14dp */
@Composable
private fun AlertRowCompact(
    displayText: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(MaterialTheme.colors.surface)
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = displayText,
            color = MaterialTheme.colors.onBackground,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Button(
            onClick = onDelete,
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface)
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colors.onBackground,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/* ------------ EDIT ALERT SCREEN ------------ */
@Composable
private fun EditAlertScreen(
    index: Int,
    initialText: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepo(context) }
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(initialText) }

    val listState = rememberScalingLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 0),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    text = "Edit alert",
                    color = MaterialTheme.colors.primary,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    textAlign = TextAlign.Center
                )
            }

            item {
                val shape = RoundedCornerShape(16.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(shape)
                        .background(MaterialTheme.colors.surface)
                        .clickable {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            Log.d(TAG, "Editing alert text: '$text'")
                        },
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
                        textStyle = TextStyle(
                            color = MaterialTheme.colors.onBackground,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "Alert text",
                                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactChip(
                        onClick = { onBack() },
                        label = {
                            Text(
                                text = "Back",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = {
                            Log.d(TAG, "Save edited alert at index=$index text='$text'")
                            scope.launch {
                                val current = SettingsRepo(context).alertsFlow.first().toMutableList()
                                if (index in current.indices) {
                                    current[index] = text
                                    SettingsRepo(context).saveAlerts(current)
                                    Log.d(TAG, "Edited alert saved: $current")
                                } else {
                                    Log.w(TAG, "Index out of bounds while saving edited alert")
                                }
                            }
                            onSaved()
                        },
                        label = {
                            Text(
                                text = "Save",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/* ------------ FORMATIRANJE PRIKAZA  ------------ */
private fun formatAmountLabel(raw: String): String {
    val normalized = raw.trim().replace(',', '.')
    return try {
        val bd = BigDecimal(normalized)
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            decimalSeparator = '.'
            groupingSeparator = ','
        }
        val df = DecimalFormat("0.00", symbols)
        "${df.format(bd)} eur"
    } catch (e: Exception) {
        raw
    }
}

/* ------------ PREVIEWS ------------ */
@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewHome() { AppTheme { HomeScreen(onOpenSetup = {}) } }

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewSetup() { AppTheme { SetupScreen(onBack = {}, onOpenAlerts = {}, onSaved = {}) } }

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewAlerts() { AppTheme { AlertsScreen(onBack = {}, onEdit = {_,_->}) } }

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewEdit() { AppTheme { EditAlertScreen(index = 0, initialText = "3140,36", onBack = {}, onSaved = {}) } }