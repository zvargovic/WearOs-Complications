package com.example.complicationprovider
import com.example.complicationprovider.data.Snapshot
import com.example.complicationprovider.data.OneShotFetcher   // <— OVO JE KLJUČNO
import com.example.complicationprovider.data.GoldFetcher   // <— OVO JE KLJUČNO

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.TimeText
import com.example.complicationprovider.net.NetWatch
import com.example.complicationprovider.ui.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.example.complicationprovider.data.SettingsRepo
import com.example.complicationprovider.ui.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import androidx.lifecycle.lifecycleScope

private const val TAG = "Main"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }

        // Centralizirano: app launch samo osigura da je orkestrator aktivan
        Log.d(TAG, "Activity launch → ensure AlignedFetchScheduler active")

        // Pokreni (ili replaniraj) aligned raspored fetcha
        com.example.complicationprovider.orchestrator.AlignedFetchScheduler.scheduleNext(applicationContext)
    }






}

private enum class Screen { HOME, SETUP, ALERTS, EDIT_ALERT }

/* ---------------- ROOT ---------------- */
@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.HOME) }

    // shared alerts list
    var alerts by remember { mutableStateOf(listOf<String>()) }
    var editIndex by remember { mutableStateOf(-1) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onOpenSetup = { screen = Screen.SETUP }
        )
        Screen.SETUP -> SetupScreen(
            onBack = { screen = Screen.HOME },
            onOpenAlerts = { screen = Screen.ALERTS },
            onSaved = { screen = Screen.HOME }
        )
        Screen.ALERTS -> AlertsScreen(
            alerts = alerts,
            onBack = { screen = Screen.SETUP },
            onDelete = { idx -> alerts = alerts.toMutableList().also { it.removeAt(idx) } },
            onEdit = { idx -> editIndex = idx; screen = Screen.EDIT_ALERT },
            onAddNew = {
                val idx = alerts.size
                alerts = alerts + ""
                editIndex = idx
                screen = Screen.EDIT_ALERT
            }
        )
        Screen.EDIT_ALERT -> EditAlertScreen(
            initial = alerts.getOrNull(editIndex).orEmpty(),
            onBack = { screen = Screen.ALERTS },
            onSave = { newText ->
                val list = alerts.toMutableList()
                if (editIndex in list.indices) list[editIndex] = newText
                alerts = list
                screen = Screen.ALERTS
            }
        )
    }
}

/* ---------------- HOME ---------------- */
@Composable
private fun HomeScreen(onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepo(context) }

    // ⇩ KLJUČNO: ovo sluša DataStore snapshot i automatski recompose-a UI kad GoldFetcher spremi novi snapshot
    val snap by repo.snapshotFlow.collectAsState(
        initial = com.example.complicationprovider.data.Snapshot(
            usdConsensus = 0.0,
            eurConsensus = 0.0,
            eurUsdRate = 1.0,
            updatedEpochMs = 0L
        )
    )

    val usd: Double? = snap.usdConsensus.takeIf { it > 0.0 }
    val eur: Double? = snap.eurConsensus.takeIf { it > 0.0 }
    val fx : Double? = snap.eurUsdRate.takeIf { it > 0.0 }

    LaunchedEffect(snap.updatedEpochMs) {
        Log.d(TAG, "Home snapshot updated -> ts=${snap.updatedEpochMs} USD=$usd EUR=$eur FX=$fx")
    }

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.onBackground

                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onBackground,

                    )

                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "USD: ${usd?.let { euroFmt(it, '$') } ?: "—"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onBackground
                    )
                    Text(
                        text = "EUR: ${eur?.let { euroFmt(it, '€') } ?: "—"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onBackground
                    )
                    Text(
                        text = "FX: EUR/USD ${fx?.let { fxFmt(it) } ?: "—"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onBackground
                    )
                }
            }

            Button(
                onClick = onOpenSetup,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .size(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Setup",
                    tint = MaterialTheme.colors.onPrimary
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        repo.historyFlow.collect { list ->
            Log.d("HISTORY", "Ukupno = ${list.size}")
            list.takeLast(5).forEach { rec ->
                Log.d("HISTORY", "ts=${rec.ts} usd=${rec.usd} eur=${rec.eur} fx=${rec.fx}")
            }
        }
    }
}
/* ---------------- SETUP ---------------- */
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

    LaunchedEffect(Unit) {
        val s = repo.flow.first()
        apiKey = s.apiKey.orEmpty()
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
                .padding(horizontal = 12.dp),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            autoCentering = AutoCenteringParams(0),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            item {
                Text(
                    text = "Setup",
                    fontSize = 18.sp,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                Text(
                    text = "API Key",
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
                        value = apiKey,
                        onValueChange = { apiKey = it },
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
                Spacer(Modifier.height(8.dp))
            }
            item {
                Chip(
                    onClick = onOpenAlerts,
                    label = { Text("Alerts", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactChip(
                        onClick = onBack,
                        label = { Text("Back", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = {
                            scope.launch {
                                repo.saveApiKey(apiKey)
                                onSaved()
                            }
                        },
                        label = { Text("Save", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/* ---------------- ALERTS ---------------- */
@Composable
private fun AlertsScreen(
    alerts: List<String>,
    onBack: () -> Unit,
    onDelete: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAddNew: () -> Unit
) {
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
                            fontSize = 14.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    items(alerts.size) { index ->
                        AlertRowCompact(
                            displayText = formatAmountLabel(alerts[index]),
                            onClick = { onEdit(index) },
                            onDelete = { onDelete(index) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            CompactChip(
                onClick = onBack,
                label = { Text("Back", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f)
            )

            Button(
                onClick = onAddNew,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = MaterialTheme.colors.onPrimary
                )
            }
        }
    }
}

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

/* ---------------- EDIT ALERT ---------------- */
@Composable
private fun EditAlertScreen(
    initial: String,
    onBack: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val listState = rememberScalingLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
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
                                    text = "Alert text (npr. 3140.50)",
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
                        onClick = onBack,
                        label = { Text("Back", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = { onSave(text.trim()) },
                        label = { Text("Save", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/* ---------------- HELPERS ---------------- */
private fun euroFmt(v: Double, symbol: Char): String {
    val df = DecimalFormat("0.00", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' })
    return "${df.format(v)} $symbol"
}
private fun fxFmt(v: Double): String {
    val df = DecimalFormat("0.0000", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' })
    return df.format(v)
}
private fun formatAmountLabel(raw: String): String = try {
    val n = raw.trim().replace(',', '.').toDouble()
    val df = DecimalFormat("0.00", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' })
    "${df.format(n)} eur"
} catch (_: Exception) { raw }

/* ---------------- PREVIEWS ---------------- */
@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewHome() { AppTheme { HomeScreen(onOpenSetup = {}) } }

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewSetup() { AppTheme { SetupScreen(onBack = {}, onOpenAlerts = {}, onSaved = {}) } }

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewAlerts() {
    AppTheme {
        AlertsScreen(
            alerts = listOf("3140.50", "3180.00"),
            onBack = {},
            onDelete = {},
            onEdit = {},
            onAddNew = {}
        )
    }
}

@Preview(device = "wearos_small_round", showSystemUi = true)
@Composable
private fun PreviewEdit() { AppTheme { EditAlertScreen(initial = "", onBack = {}, onSave = {}) } }