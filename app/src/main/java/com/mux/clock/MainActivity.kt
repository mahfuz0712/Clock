package com.mux.clock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.time.Duration
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Store.init(this)
        Repo.init(this)
        Stopwatch.load()
        Notif.channels(this)
        Sched.scheduleAll(this)
        setContent { App() }
    }
}

enum class Tab(val label: String, val sub: String) {
    ALARM("ALARM", "ALARMS"), WORLD("WORLD", "WORLD CLOCK"), TIMER("TIMER", "COUNTDOWN"),
    WATCH("WATCH", "STOPWATCH"), SETTINGS("SYS", "SYSTEM")
}

class AlarmEdit(val item: AlarmItem?)

@Composable
fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(Tab.ALARM) }
    var alarmEdit by remember { mutableStateOf<AlarmEdit?>(null) }
    var timerDialog by remember { mutableStateOf(false) }
    var cityDialog by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var soundCb by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) { askNotif() }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val uri = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            soundCb?.invoke(uri?.toString() ?: "")
        }
    }
    fun pickSound(current: String, cb: (String) -> Unit) {
        soundCb = cb
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            if (current.isNotBlank()) putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        }
        soundLauncher.launch(i)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        alarmEdit?.let { e ->
            AlarmEditor(
                a = e.item,
                onSave = { item ->
                    val saved = if (item.id == 0) item.copy(id = Repo.newId()) else item
                    Repo.saveAlarm(saved)
                    Sched.scheduleAlarm(ctx, saved)
                    ClockWidgets.updateAll(ctx)
                    Sched.nextTrigger(saved, ZonedDateTime.now())?.let {
                        Toast.makeText(ctx, "Alarm in " + fmtIn(Duration.between(ZonedDateTime.now(), it).toMillis()), Toast.LENGTH_SHORT).show()
                    }
                    alarmEdit = null
                },
                onDelete = e.item?.let { old ->
                    {
                        Sched.cancelAlarm(ctx, old.id)
                        Repo.deleteAlarm(old.id)
                        ClockWidgets.updateAll(ctx)
                        alarmEdit = null
                    }
                },
                onClose = { alarmEdit = null },
                onPickSound = { cur, cb -> pickSound(cur, cb) }
            )
        }
        if (timerDialog) {
            TimerCreate(
                onCreate = { total, label -> Timers.add(ctx, total, label, true); timerDialog = false },
                onClose = { timerDialog = false }
            )
        }
        if (cityDialog) {
            ZonePicker(onPick = { Repo.addCity(it); ClockWidgets.updateAll(ctx); cityDialog = false }, onClose = { cityDialog = false })
        }

        HudBackground {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                HudHeader("CLOCK", tab.sub)
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
                    when (tab) {
                        Tab.ALARM -> AlarmScreen(onEdit = { alarmEdit = AlarmEdit(it) }, onPickSound = { cur, cb -> pickSound(cur, cb) })
                        Tab.WORLD -> WorldScreen(onAdd = { cityDialog = true })
                        Tab.TIMER -> TimerScreen(onCreate = { timerDialog = true })
                        Tab.WATCH -> StopwatchScreen()
                        Tab.SETTINGS -> SettingsScreen(
                            onPickSound = { cur, cb -> pickSound(cur, cb) },
                            onNotifPermission = { askNotif() },
                            onUpdateFound = { update = it }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(14.dp, 8.dp, 14.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in Tab.entries) HudButton(t.label, tab == t, Modifier.weight(1f), 50.dp, 11f) { tab = t }
                }
            }
        }
    }
}
