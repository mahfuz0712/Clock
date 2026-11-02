package com.mux.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZonedDateTime

data class RingInfo(val kind: String, val title: String, val snoozeMin: Int)

/** What is ringing right now (read by the full-screen ring activity). */
object Ringing {
    var info by mutableStateOf<RingInfo?>(null)
}

object Notif {
    const val CH_ALARM = "mux_clock_alarm"
    const val CH_TIMER = "mux_clock_timer"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Alarms and timers", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when an alarm or timer goes off"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Running timers", NotificationManager.IMPORTANCE_LOW))
    }
}

// ---------------------------------------------------------------- scheduling
object Sched {
    const val ACTION_ALARM = "com.mux.clock.ALARM"
    const val ACTION_SNOOZE_FIRE = "com.mux.clock.SNOOZE_FIRE"
    const val ACTION_TIMER = "com.mux.clock.TIMER"

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun op(ctx: Context, action: String, code: Int, id: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("mux-clock://$action/$id")).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun showApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun nextTrigger(a: AlarmItem, now: ZonedDateTime): ZonedDateTime? {
        if (!a.enabled) return null
        for (i in 0..7) {
            val d = now.toLocalDate().plusDays(i.toLong())
            if (a.days != 0 && (a.days and (1 shl (d.dayOfWeek.value - 1))) == 0) continue
            val t = ZonedDateTime.of(d, LocalTime.of(a.hour, a.minute), now.zone)
            if (t.isAfter(now)) return t
        }
        return null
    }

    fun next(list: List<AlarmItem>, now: ZonedDateTime): Pair<AlarmItem, ZonedDateTime>? =
        list.mapNotNull { a -> nextTrigger(a, now)?.let { a to it } }.minByOrNull { it.second.toInstant() }

    fun scheduleAlarm(ctx: Context, a: AlarmItem) {
        cancelAlarm(ctx, a.id)
        val t = nextTrigger(a, ZonedDateTime.now()) ?: return
        val ms = t.toInstant().toEpochMilli()
        val pi = op(ctx, ACTION_ALARM, a.id, a.id)
        try {
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(ms, showApp(ctx)), pi)
        } catch (e: SecurityException) {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun cancelAlarm(ctx: Context, id: Int) { am(ctx).cancel(op(ctx, ACTION_ALARM, id, id)) }

    fun setEnabled(ctx: Context, a: AlarmItem, on: Boolean) {
        val n = a.copy(enabled = on)
        Repo.saveAlarm(n)
        if (on) scheduleAlarm(ctx, n) else cancelAlarm(ctx, n.id)
        ClockWidgets.updateAll(ctx)
    }

    fun scheduleAll(ctx: Context) {
        Repo.alarms.forEach { if (it.enabled) scheduleAlarm(ctx, it) else cancelAlarm(ctx, it.id) }
        ClockWidgets.updateAll(ctx)
    }

    fun scheduleSnooze(ctx: Context, id: Int, minutes: Int) {
        val ms = System.currentTimeMillis() + minutes * 60_000L
        val pi = op(ctx, ACTION_SNOOZE_FIRE, 500_000 + id, id)
        try {
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(ms, showApp(ctx)), pi)
        } catch (e: SecurityException) {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun scheduleTimer(ctx: Context, t: TimerItem) {
        val pi = op(ctx, ACTION_TIMER, 700_000 + t.id, t.id)
        try {
            if (canExact(ctx)) am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endWall, pi)
            else am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endWall, pi)
        } catch (e: SecurityException) {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endWall, pi)
        }
    }

    fun cancelTimer(ctx: Context, id: Int) { am(ctx).cancel(op(ctx, ACTION_TIMER, 700_000 + id, id)) }
}

// ---------------------------------------------------------------- timers
object Timers {
    fun add(ctx: Context, totalMs: Long, label: String, startNow: Boolean): Int {
        val t = TimerItem(Repo.newId(), label, totalMs)
        Repo.saveTimer(t)
        if (startNow) start(ctx, t.id)
        return t.id
    }

    fun start(ctx: Context, id: Int) {
        val t = Repo.timers.firstOrNull { it.id == id } ?: return
        val n = t.copy(state = 1, endWall = System.currentTimeMillis() + t.remainingMs)
        Repo.saveTimer(n)
        Sched.scheduleTimer(ctx, n)
        TimerNotif.refresh(ctx)
    }

    fun pause(ctx: Context, id: Int) {
        val t = Repo.timers.firstOrNull { it.id == id } ?: return
        Repo.saveTimer(t.copy(state = 2, remainingMs = (t.endWall - System.currentTimeMillis()).coerceAtLeast(0L), endWall = 0L))
        Sched.cancelTimer(ctx, id)
        TimerNotif.refresh(ctx)
    }

    fun reset(ctx: Context, id: Int) {
        val t = Repo.timers.firstOrNull { it.id == id } ?: return
        Repo.saveTimer(t.copy(state = 0, remainingMs = t.totalMs, endWall = 0L))
        Sched.cancelTimer(ctx, id)
        TimerNotif.refresh(ctx)
    }

    fun delete(ctx: Context, id: Int) {
        Sched.cancelTimer(ctx, id)
        Repo.deleteTimer(id)
        TimerNotif.refresh(ctx)
    }
}

/** Ongoing notification with a live countdown while timers are running. */
object TimerNotif {
    private const val ID = 900

    fun refresh(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val running = Repo.timers.filter { it.state == 1 }
        if (running.isEmpty()) { nm.cancel(ID); return }
        Notif.channels(ctx)
        val soon = running.minByOrNull { it.endWall } ?: return
        val open = PendingIntent.getActivity(
            ctx, 6, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Notif.CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(if (running.size > 1) "${running.size} timers running" else soon.label.ifBlank { "Timer" })
            .setContentText("Next one ends soon")
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(soon.endWall)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
        try { nm.notify(ID, n) } catch (e: SecurityException) { }
    }
}

// ---------------------------------------------------------------- receivers
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        Store.init(ctx)
        val id = intent.getIntExtra("id", -1)
        when (intent.action) {
            Sched.ACTION_ALARM, Sched.ACTION_SNOOZE_FIRE -> {
                val a = Repo.alarms.firstOrNull { it.id == id } ?: return
                ring(ctx, "alarm", a.id, a.label.ifBlank { "Alarm" }, a.sound, a.vibrate, a.snoozeMin)
                if (intent.action == Sched.ACTION_ALARM) {
                    if (a.days == 0) Repo.saveAlarm(a.copy(enabled = false)) else Sched.scheduleAlarm(ctx, a)
                }
                ClockWidgets.updateAll(ctx)
            }
            Sched.ACTION_TIMER -> {
                val t = Repo.timers.firstOrNull { it.id == id } ?: return
                Repo.saveTimer(t.copy(state = 0, remainingMs = t.totalMs, endWall = 0L))
                ring(ctx, "timer", t.id, t.label.ifBlank { "Timer" }, "", true, 0)
                TimerNotif.refresh(ctx)
            }
        }
    }

    private fun ring(ctx: Context, kind: String, id: Int, title: String, sound: String, vibrate: Boolean, snooze: Int) {
        val i = Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_RING)
            .putExtra("kind", kind).putExtra("id", id).putExtra("title", title)
            .putExtra("sound", sound).putExtra("vibrate", vibrate).putExtra("snooze", snooze)
        ContextCompat.startForegroundService(ctx, i)
    }
}

/** Re-plans everything after reboot, app update, time or time zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        Store.init(ctx)
        Sched.scheduleAll(ctx)
        val now = System.currentTimeMillis()
        Repo.timers.filter { it.state == 1 }.forEach { t ->
            if (t.endWall > now) Sched.scheduleTimer(ctx, t)
            else Repo.saveTimer(t.copy(state = 0, remainingMs = t.totalMs, endWall = 0L))
        }
        TimerNotif.refresh(ctx)
    }
}

// ---------------------------------------------------------------- ringing service
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "com.mux.clock.RING"
        const val ACTION_STOP = "com.mux.clock.STOP"
        const val ACTION_SNOOZE = "com.mux.clock.SNOOZE"
        private const val NOTIF_ID = 1000
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var kind = "alarm"
    private var refId = 0
    private var title = ""
    private var snoozeMin = 0
    private var vol = 1f

    private val ramp = object : Runnable {
        override fun run() {
            vol = (vol + 0.06f).coerceAtMost(1f)
            try { player?.setVolume(vol, vol) } catch (e: Exception) { }
            if (vol < 1f) handler.postDelayed(this, 1500)
        }
    }
    private val timeout = Runnable {
        val k = kind
        val t = title
        stopRinging()
        missed(k, t)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRinging(); stopSelf() }
            ACTION_SNOOZE -> {
                if (kind == "alarm" && snoozeMin > 0) Sched.scheduleSnooze(this, refId, snoozeMin)
                stopRinging()
                stopSelf()
            }
            else -> if (intent != null) startRinging(intent) else stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    private fun startRinging(i: Intent) {
        Store.init(this)
        Repo.init(this)
        stopRinging(silent = true)
        kind = i.getStringExtra("kind") ?: "alarm"
        refId = i.getIntExtra("id", 0)
        title = i.getStringExtra("title") ?: ""
        snoozeMin = i.getIntExtra("snooze", 0)
        Ringing.info = RingInfo(kind, title, snoozeMin)
        Notif.channels(this)
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        play(i.getStringExtra("sound") ?: "")
        if (i.getBooleanExtra("vibrate", true)) vibrate()
        if (Store.silenceAfter > 0) handler.postDelayed(timeout, Store.silenceAfter * 60_000L)
    }

    private fun stopRinging(silent: Boolean = false) {
        handler.removeCallbacks(ramp)
        handler.removeCallbacks(timeout)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
        if (!silent) {
            Ringing.info = null
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun play(pref: String) {
        val uris = listOf(pref, Store.defaultSound).filter { it.isNotBlank() }.map { Uri.parse(it) } +
            listOfNotNull(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            )
        for (uri in uris) {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                p.setDataSource(this, uri)
                p.isLooping = true
                p.prepare()
                vol = if (Store.gradual) 0.15f else 1f
                p.setVolume(vol, vol)
                p.start()
                player = p
                if (Store.gradual) handler.postDelayed(ramp, 1500)
                return
            } catch (e: Exception) { }
        }
    }

    private fun vibrate() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        try { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)) } catch (e: Exception) { }
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val full = PendingIntent.getActivity(
            this, 1, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), flags
        )
        val stop = PendingIntent.getService(this, 2, Intent(this, AlarmService::class.java).setAction(ACTION_STOP), flags)
        val snooze = PendingIntent.getService(this, 3, Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE), flags)
        val b = NotificationCompat.Builder(this, Notif.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(if (kind == "timer") "Timer finished" else "Alarm")
            .setContentText(title)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(0, if (kind == "timer") "Stop" else "Dismiss", stop)
        if (kind == "alarm" && snoozeMin > 0) b.addAction(0, "Snooze $snoozeMin min", snooze)
        return b.build()
    }

    private fun missed(k: String, t: String) {
        val open = PendingIntent.getActivity(
            this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, Notif.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(if (k == "timer") "Missed timer" else "Missed alarm")
            .setContentText(t).setAutoCancel(true).setContentIntent(open).build()
        try { getSystemService(NotificationManager::class.java).notify(2000 + refId, n) } catch (e: SecurityException) { }
    }
}

// ---------------------------------------------------------------- full-screen ring screen
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val info = Ringing.info
            LaunchedEffect(info) { if (info == null) finish() }
            BackHandler(true) { }
            if (info != null) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    RingScreen(info, onSnooze = { send(AlarmService.ACTION_SNOOZE) }, onStop = { send(AlarmService.ACTION_STOP) })
                }
            }
        }
    }

    private fun send(action: String) { startService(Intent(this, AlarmService::class.java).setAction(action)) }
}

@Composable
fun RingScreen(info: RingInfo, onSnooze: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(1000) } }
    val timer = info.kind == "timer"
    HudBackground {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(14.dp)) {
            HudHeader("CLOCK", if (timer) "TIMER" else "ALARM")
            HudCard(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), tag = if (timer) "TIME UP" else "WAKE UP", glow = true) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    T(fmtClock(now), 44f, cMain, true)
                    T(fmtDate(now), 13f, cSub, mod = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(28.dp))
                    T(info.title.uppercase(), 22f, cSecond, true, Modifier.padding(horizontal = 12.dp), 3, TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!timer && info.snoozeMin > 0) {
                    HudButton("SNOOZE ${info.snoozeMin} MIN", false, Modifier.weight(1f), 72.dp, 14f, cSecond) { onSnooze() }
                }
                HudButton(if (timer) "STOP" else "DISMISS", true, Modifier.weight(1f), 72.dp, 16f) { onStop() }
            }
        }
    }
}
