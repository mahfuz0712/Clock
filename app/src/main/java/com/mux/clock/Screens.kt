package com.mux.clock

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

fun zoneOf(id: String): ZoneId = try { ZoneId.of(id) } catch (e: Exception) { ZoneId.systemDefault() }

fun soundTitle(ctx: Context, s: String): String =
    if (s.isBlank()) "DEFAULT"
    else try { RingtoneManager.getRingtone(ctx, Uri.parse(s))?.getTitle(ctx)?.uppercase() ?: "CUSTOM" } catch (e: Exception) { "CUSTOM" }

private fun fmtOffset(min: Int): String = if (min % 60 == 0) "${min / 60}H" else "${min / 60}H ${min % 60}M"

// ---------------------------------------------------------------- alarm
@Composable
private fun AlarmRow(a: AlarmItem, onToggle: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .hudPanel(if (a.enabled) Color(0xFF071A22) else PanelBg, if (a.enabled) cLine else cMain.copy(alpha = 0.3f), 10.dp)
            .clip(ChamferShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            T(fmtT(a.hour, a.minute), 30f, if (a.enabled) cMain else cSub, true)
            T(a.label.ifBlank { "ALARM" }.uppercase() + "  " + daysText(a.days), 11f, cSub, lines = 2)
        }
        Chip(if (a.enabled) "ON" else "OFF", a.enabled, Modifier.width(66.dp), onToggle)
    }
}

@Composable
fun AlarmScreen(onEdit: (AlarmItem?) -> Unit, onPickSound: (String, (String) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(10_000) } }
    val next = remember(Repo.alarms, now) { Sched.next(Repo.alarms, now) }
    val sorted = remember(Repo.alarms) { Repo.alarms.sortedBy { it.hour * 60 + it.minute } }
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "ALARMS") {
            if (sorted.isEmpty()) T("NO ALARMS YET. TAP [+ NEW ALARM].", 12f, cSub, lines = 2)
            LazyColumn(Modifier.fillMaxSize()) {
                items(sorted, key = { it.id }) { a -> AlarmRow(a, { Sched.setEnabled(ctx, a, !a.enabled) }, { onEdit(a) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = if (next == null) "ALL ALARMS OFF" else "RINGS IN ${fmtIn(Duration.between(now, next.second).toMillis())}",
            info = if (next == null) "TAP [+ NEW ALARM] TO ADD ONE" else "${fmtDay(next.second)} ${fmtT(next.second)}  ${next.first.label.ifBlank { "ALARM" }.uppercase()}"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("+ NEW ALARM", true, Modifier.weight(1f), textSize = 14f) { onEdit(null) }
            HudButton("DEFAULT TONE", false, Modifier.weight(1f), textSize = 14f) { onPickSound(Store.defaultSound) { Store.updateSound(it) } }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun AlarmEditor(
    a: AlarmItem?, onSave: (AlarmItem) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit,
    onPickSound: (String, (String) -> Unit) -> Unit
) {
    val ctx = LocalContext.current
    var hour by remember { mutableIntStateOf(a?.hour ?: ((ZonedDateTime.now().hour + 1) % 24)) }
    var minute by remember { mutableIntStateOf(a?.minute ?: 0) }
    var label by remember { mutableStateOf(a?.label ?: "") }
    var days by remember { mutableIntStateOf(a?.days ?: 0) }
    var vibrate by remember { mutableStateOf(a?.vibrate ?: true) }
    var sound by remember { mutableStateOf(a?.sound ?: "") }
    var snooze by remember { mutableIntStateOf(a?.snoozeMin ?: Store.defaultSnooze) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().hudPanel(PanelBg, cLine, 16.dp).padding(18.dp).verticalScroll(rememberScrollState())) {
            T(if (a == null) "// NEW ALARM" else "// EDIT ALARM", 12f, cSub, true)
            Box(
                Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    .hudPanel(Color(0xFF06131A), cLine, 12.dp).clip(ChamferShape(12.dp))
                    .clickable { TimePickerDialog(ctx, { _, h, m -> hour = h; minute = m }, hour, minute, Store.use24h).show() }
                    .padding(vertical = 16.dp),
                Alignment.Center
            ) { T(fmtT(hour, minute), 44f, cMain, true) }
            T("LABEL", 10f, cSub, true)
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).hudPanel(Color(0xFF06131A), cMain.copy(alpha = 0.4f), 8.dp).padding(10.dp)) {
                if (label.isEmpty()) T("ALARM", 13f, cSub)
                BasicTextField(
                    label, { label = it }, singleLine = true,
                    textStyle = TextStyle(color = cWhite, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            T("REPEAT  (NONE = ONCE)", 10f, cSub, true, Modifier.padding(top = 14.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until 7) {
                    val on = days and (1 shl i) != 0
                    Chip(dayName(i).take(2), on, Modifier.weight(1f)) { days = days xor (1 shl i) }
                }
            }
            T("VIBRATE", 10f, cSub, true, Modifier.padding(top = 14.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("ON", vibrate, Modifier.weight(1f)) { vibrate = true }
                Chip("OFF", !vibrate, Modifier.weight(1f)) { vibrate = false }
            }
            T("SOUND", 10f, cSub, true, Modifier.padding(top = 14.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(soundTitle(ctx, sound), 12f, cWhite, mod = Modifier.weight(1f), lines = 2)
                Chip("CHANGE", false, Modifier.width(96.dp)) { onPickSound(sound) { sound = it } }
            }
            T("SNOOZE", 10f, cSub, true, Modifier.padding(top = 14.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "OFF", 5 to "5 MIN", 10 to "10 MIN", 15 to "15 MIN").forEach { (m, l) ->
                    Chip(l, snooze == m, Modifier.weight(1f)) { snooze = m }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudButton("SAVE", true, Modifier.weight(1f), 50.dp, 13f) {
                    onSave((a ?: AlarmItem(0, hour, minute)).copy(
                        hour = hour, minute = minute, label = label.trim(), days = days, enabled = true,
                        vibrate = vibrate, sound = sound, snoozeMin = snooze
                    ))
                }
                if (onDelete != null) HudButton("DELETE", false, Modifier.weight(1f), 50.dp, 13f, cRed) { onDelete() }
                HudButton("CANCEL", false, Modifier.weight(1f), 50.dp, 13f, onClick = onClose)
            }
        }
    }
}

// ---------------------------------------------------------------- world clock
@Composable
fun ZonePicker(onPick: (String) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') || it == "UTC" }.sorted() }
    val shown = remember(q) { all.filter { q.isBlank() || it.contains(q.trim().replace(' ', '_'), true) }.take(150) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).hudPanel(PanelBg, cLine, 16.dp).padding(16.dp)) {
            T("// ADD CITY", 12f, cSub, true)
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).hudPanel(Color(0xFF06131A), cMain.copy(alpha = 0.4f), 8.dp).padding(10.dp)) {
                if (q.isEmpty()) T("SEARCH CITY OR REGION...", 13f, cSub)
                BasicTextField(
                    q, { q = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 6.dp)) {
                items(shown) { id ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(cityName(id).uppercase(), 13f, cWhite, true, Modifier.weight(1f))
                        T(id.substringBefore('/'), 10f, cSub)
                    }
                }
            }
        }
    }
}

@Composable
private fun CityRow(id: String, inst: Instant, home: ZoneId) {
    val z = inst.atZone(zoneOf(id))
    val hz = inst.atZone(home)
    val diff = (z.offset.totalSeconds - hz.offset.totalSeconds) / 60
    val rel = if (diff == 0) "SAME TIME" else (if (diff > 0) "+" else "-") + fmtOffset(abs(diff)) + (if (diff > 0) " AHEAD" else " BEHIND")
    val dd = z.toLocalDate().toEpochDay() - hz.toLocalDate().toEpochDay()
    val dayText = when (dd) { 0L -> "TODAY"; 1L -> "TOMORROW"; -1L -> "YESTERDAY"; else -> fmtDay(z) }
    val night = z.hour < 6 || z.hour >= 19
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).hudPanel(Color(0xFF071A22), cMain.copy(alpha = 0.4f), 8.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            T(cityName(id).uppercase(), 15f, cWhite, true)
            T("$rel  $dayText", 10f, cSub, lines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            T(fmtT(z), 22f, cMain, true)
            T(if (night) "NIGHT" else "DAY", 9f, if (night) cSub else cSecond, true)
        }
        T("[X]", 11f, cRed, mod = Modifier.clickable { Repo.removeCity(id) }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
    }
}

@Composable
fun WorldScreen(onAdd: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1000L - System.currentTimeMillis() % 1000L) } }
    var shift by remember { mutableIntStateOf(0) } // minutes
    var travel by remember { mutableStateOf(false) }
    val home = ZoneId.systemDefault()
    val inst = now.plusSeconds(shift * 60L)
    val hz = inst.atZone(home)
    val shiftText = (if (shift >= 0) "+" else "-") + fmtOffset(abs(shift))
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "WORLD CLOCK", glow = true) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        T(fmtClock(hz), 42f, if (shift != 0) cSecond else cMain, true)
                        T(fmtDate(hz), 13f, cSub)
                        T(zoneLabel(home), 10f, cSub, lines = 2, align = TextAlign.Center)
                        if (shift != 0) T("PLANNING $shiftText", 11f, cSecond, true, Modifier.padding(top = 4.dp))
                    }
                }
                items(Repo.cities, key = { it }) { id -> CityRow(id, inst, home) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (travel) {
            Column(Modifier.fillMaxWidth().hudPanel(PanelBg, cLine, 12.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("TIME TRAVEL  $shiftText", 11f, cSecond, true, Modifier.weight(1f))
                    if (shift != 0) T("[RESET]", 11f, cMain, mod = Modifier.clickable { shift = 0 }.padding(6.dp))
                }
                Slider(
                    value = shift / 60f, onValueChange = { shift = (it * 2).roundToInt() * 30 },
                    valueRange = -24f..24f, colors = sliderColors()
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        StatusCard(
            title = "HOME  ${cityName(home.id).uppercase()}",
            info = "${Repo.cities.size} CITIES  ·  DRAG TIME TRAVEL TO PLAN A CALL"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("+ ADD CITY", true, Modifier.weight(1f), textSize = 14f) { onAdd() }
            HudButton(if (travel) "HIDE SLIDER" else "TIME TRAVEL", false, Modifier.weight(1f), textSize = 14f) { travel = !travel }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- timer
@Composable
private fun Stepper(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T("[+]", 16f, cMain, true, Modifier.clickable { onChange(if (value >= max) 0 else value + 1) }.padding(8.dp))
        T("%02d".format(value), 34f, cWhite, true)
        T("[-]", 16f, cMain, true, Modifier.clickable { onChange(if (value <= 0) max else value - 1) }.padding(8.dp))
        T(label, 10f, cSub)
    }
}

@Composable
fun TimerCreate(onCreate: (Long, String) -> Unit, onClose: () -> Unit) {
    var h by remember { mutableIntStateOf(0) }
    var m by remember { mutableIntStateOf(5) }
    var s by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().hudPanel(PanelBg, cLine, 16.dp).padding(18.dp)) {
            T("// NEW TIMER", 12f, cSub, true)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stepper("HOURS", h, 23) { h = it }
                Stepper("MIN", m, 59) { m = it }
                Stepper("SEC", s, 59) { s = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("1M" to 60, "5M" to 300, "10M" to 600, "15M" to 900, "30M" to 1800, "1H" to 3600).forEach { (l, sec) ->
                    Chip(l, false, Modifier.weight(1f)) { h = sec / 3600; m = sec / 60 % 60; s = sec % 60 }
                }
            }
            T("LABEL", 10f, cSub, true, Modifier.padding(top = 14.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).hudPanel(Color(0xFF06131A), cMain.copy(alpha = 0.4f), 8.dp).padding(10.dp)) {
                if (label.isEmpty()) T("TIMER", 13f, cSub)
                BasicTextField(
                    label, { label = it }, singleLine = true,
                    textStyle = TextStyle(color = cWhite, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("START", true, Modifier.weight(1f), 50.dp, 13f) {
                    val total = (h * 3600L + m * 60L + s) * 1000L
                    if (total > 0) onCreate(total, label.trim())
                }
                HudButton("CANCEL", false, Modifier.weight(1f), 50.dp, 13f, onClick = onClose)
            }
        }
    }
}

@Composable
private fun TimerRow(t: TimerItem, now: Long, ctx: Context) {
    val rem = if (t.state == 1) (t.endWall - now).coerceAtLeast(0L) else t.remainingMs
    val frac = if (t.totalMs > 0) (rem.toFloat() / t.totalMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).hudPanel(Color(0xFF071A22), cLine, 10.dp).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(t.label.ifBlank { "TIMER" }.uppercase() + "  " + fmtTimer(t.totalMs), 11f, cSub, mod = Modifier.weight(1f))
            T("[DEL]", 11f, cRed, mod = Modifier.clickable { Timers.delete(ctx, t.id) }.padding(6.dp))
        }
        T(fmtTimer(rem), 40f, if (t.state == 1) cSecond else cMain, true, Modifier.padding(vertical = 4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(cDim)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (t.state == 1) cSecond else cMain))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (t.state == 1) "PAUSE" else "START", t.state != 1, Modifier.weight(1f), 46.dp, 13f) {
                if (t.state == 1) Timers.pause(ctx, t.id) else Timers.start(ctx, t.id)
            }
            HudButton("RESET", false, Modifier.weight(1f), 46.dp, 13f) { Timers.reset(ctx, t.id) }
        }
    }
}

@Composable
fun TimerScreen(onCreate: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(200) } }
    val running = Repo.timers.count { it.state == 1 }
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "TIMERS") {
            if (Repo.timers.isEmpty()) T("NO TIMERS. TAP [+ NEW TIMER].", 12f, cSub, lines = 2)
            LazyColumn(Modifier.fillMaxSize()) {
                items(Repo.timers, key = { it.id }) { t -> TimerRow(t, now, ctx) }
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = if (running == 0) "NO TIMER RUNNING" else "$running RUNNING",
            info = "${Repo.timers.size} TIMERS  ·  THEY RING EVEN WHEN THE APP IS CLOSED"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton("+ NEW TIMER", true, Modifier.weight(1f), textSize = 14f) { onCreate() }
            HudButton("+ QUICK 5 MIN", false, Modifier.weight(1f), textSize = 14f) { Timers.add(ctx, 5 * 60_000L, "", true) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- stopwatch
@Composable
fun StopwatchScreen() {
    var ms by remember { mutableLongStateOf(Stopwatch.elapsed()) }
    LaunchedEffect(Unit) { while (true) { ms = Stopwatch.elapsed(); delay(33) } }
    val laps = Stopwatch.laps
    val lapTimes = remember(laps) { laps.mapIndexed { i, t -> t - (if (i == 0) 0L else laps[i - 1]) } }
    val best = if (lapTimes.size >= 2) lapTimes.min() else -1L
    val worst = if (lapTimes.size >= 2) lapTimes.max() else -1L
    val running = Stopwatch.running
    Column(Modifier.fillMaxSize()) {
        HudCard(Modifier.weight(1f).fillMaxWidth(), tag = "STOPWATCH", glow = true) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), Alignment.Center) {
                    T(fmtWatch(ms), 44f, if (running) cSecond else cMain, true)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(lapTimes.size) { idx ->
                        val i = lapTimes.size - 1 - idx
                        val lt = lapTimes[i]
                        val col = if (lt == best) cMain else if (lt == worst) cRed else cWhite
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            T("LAP %02d".format(i + 1), 12f, cSub, mod = Modifier.width(80.dp))
                            T(fmtWatch(lt), 14f, col, true, Modifier.weight(1f))
                            T(fmtWatch(laps[i]), 12f, cSub)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StatusCard(
            title = if (running) "RUNNING" else if (ms > 0) "STOPPED" else "READY",
            info = if (laps.isEmpty()) "PRESS START" else "${laps.size} LAPS  ·  BEST ${if (best >= 0) fmtWatch(best) else "-"}"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if (running) "STOP" else "START", true, Modifier.weight(1f), 66.dp, 17f) { if (running) Stopwatch.stop() else Stopwatch.start() }
            HudButton(if (running) "LAP" else "RESET", false, Modifier.weight(1f), 66.dp, 17f) { if (running) Stopwatch.lap() else Stopwatch.reset() }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- settings
@Composable
fun SettingsScreen(
    onPickSound: (String, (String) -> Unit) -> Unit, onNotifPermission: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var widgetNote by remember { mutableStateOf("") }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 20.dp, bottom = 8.dp))
    @Composable fun options(labels: List<String>, selected: Int, set: (Int) -> Unit) =
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l -> Chip(l, selected == i, Modifier.weight(1f)) { set(i) } }
        }
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val notifOk = nm.areNotificationsEnabled()
    val exactOk = Sched.canExact(ctx)
    val fullOk = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()

    HudCard(Modifier.fillMaxSize(), tag = "SYSTEM") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            section("INTERFACE COLOR")
            options(ACCENTS.map { it.name }, Store.accent) { Store.updateAccent(it) }

            section("TIME FORMAT")
            options(listOf("24 HOUR", "12 HOUR"), if (Store.use24h) 0 else 1) { Store.updateUse24h(it == 0) }

            section("SECONDS ON THE BIG CLOCK")
            options(listOf("SHOW", "HIDE"), if (Store.showSeconds) 0 else 1) { Store.updateShowSeconds(it == 0) }

            section("DEFAULT SNOOZE")
            val sn = listOf(5, 10, 15)
            options(listOf("5 MIN", "10 MIN", "15 MIN"), sn.indexOf(Store.defaultSnooze).coerceAtLeast(0)) { Store.updateSnooze(sn[it]) }

            section("STOP RINGING AFTER")
            val si = listOf(1, 5, 10, 0)
            options(listOf("1 MIN", "5 MIN", "10 MIN", "NEVER"), si.indexOf(Store.silenceAfter).coerceAtLeast(0)) { Store.updateSilence(si[it]) }

            section("VOLUME")
            options(listOf("GRADUAL RISE", "FULL AT ONCE"), if (Store.gradual) 0 else 1) { Store.updateGradual(it == 0) }

            section("DEFAULT ALARM SOUND")
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(soundTitle(ctx, Store.defaultSound), 12f, cWhite, mod = Modifier.weight(1f), lines = 2)
                Chip("CHANGE", false, Modifier.width(96.dp)) { onPickSound(Store.defaultSound) { Store.updateSound(it) } }
            }

            section("PERMISSIONS")
            T("NOTIFICATIONS: " + if (notifOk) "ALLOWED" else "BLOCKED", 11f, if (notifOk) cMain else cRed)
            T("EXACT ALARMS: " + if (exactOk) "ALLOWED" else "NOT ALLOWED", 11f, if (exactOk) cMain else cRed, mod = Modifier.padding(top = 4.dp))
            T("FULL-SCREEN ALERTS: " + if (fullOk) "ALLOWED" else "BLOCKED", 11f, if (fullOk) cMain else cRed, mod = Modifier.padding(top = 4.dp))
            if (!notifOk) Chip("ALLOW NOTIFICATIONS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) { onNotifPermission() }
            if (!exactOk && Build.VERSION.SDK_INT >= 31) {
                Chip("ALLOW EXACT ALARMS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) } catch (e: Exception) { }
                }
            }
            if (!fullOk && Build.VERSION.SDK_INT >= 34) {
                Chip("ALLOW FULL-SCREEN ALERTS", true, Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) } catch (e: Exception) { }
                }
            }

            section("HOME SCREEN WIDGETS")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("DIGITAL", false, Modifier.weight(1f)) {
                    widgetNote = if (ClockWidgets.pin(ctx, DigitalWidget::class.java)) "CONFIRM IN THE POP-UP" else "ADD IT FROM YOUR LAUNCHER'S WIDGET MENU"
                }
                Chip("ANALOG", false, Modifier.weight(1f)) {
                    widgetNote = if (ClockWidgets.pin(ctx, AnalogWidget::class.java)) "CONFIRM IN THE POP-UP" else "ADD IT FROM YOUR LAUNCHER'S WIDGET MENU"
                }
                Chip("WORLD", false, Modifier.weight(1f)) {
                    widgetNote = if (ClockWidgets.pin(ctx, WorldWidget::class.java)) "CONFIRM IN THE POP-UP" else "ADD IT FROM YOUR LAUNCHER'S WIDGET MENU"
                }
            }
            if (widgetNote.isNotEmpty()) T(widgetNote, 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)

            section("UPDATES")
            Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
                scope.launch {
                    status = "CHECKING..."
                    if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                    val (ok, info) = checkUpdate(ctx)
                    status = when {
                        !ok -> "CHECK FAILED"
                        info == null -> "UP TO DATE"
                        else -> "NEW VERSION AVAILABLE: v${info.version}"
                    }
                    if (info != null) onUpdateFound(info)
                }
            }
            if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

            section("ABOUT")
            T("CLOCK v${appVersion(ctx)}", 13f, cMain, true)
            T("CLOCK CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
            T("DATA: STORED ON THIS DEVICE ONLY", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp))
            T("DEV: ${AppInfo.DEV_NAME}", 12f, cWhite)
            T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
            T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

            section("MORE APPS")
            T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
            T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
            Spacer(Modifier.height(8.dp))
        }
    }
}
