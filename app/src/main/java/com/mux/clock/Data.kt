package com.mux.clock

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- models
/** days: bit 0 = Monday ... bit 6 = Sunday, 0 = ring once. */
data class AlarmItem(
    val id: Int, val hour: Int, val minute: Int, val label: String = "", val days: Int = 0,
    val enabled: Boolean = true, val vibrate: Boolean = true, val sound: String = "", val snoozeMin: Int = 10
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("h", hour).put("m", minute).put("label", label)
        .put("days", days).put("on", enabled).put("vib", vibrate).put("sound", sound).put("snooze", snoozeMin)

    companion object {
        fun from(o: JSONObject) = AlarmItem(
            o.getInt("id"), o.getInt("h"), o.getInt("m"), o.optString("label"), o.optInt("days"),
            o.optBoolean("on", true), o.optBoolean("vib", true), o.optString("sound"), o.optInt("snooze", 10)
        )
    }
}

/** state: 0 idle, 1 running, 2 paused. */
data class TimerItem(
    val id: Int, val label: String, val totalMs: Long, val state: Int = 0,
    val remainingMs: Long = totalMs, val endWall: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("total", totalMs)
        .put("state", state).put("rem", remainingMs).put("end", endWall)

    companion object {
        fun from(o: JSONObject) = TimerItem(
            o.getInt("id"), o.optString("label"), o.getLong("total"), o.optInt("state"), o.getLong("rem"), o.optLong("end")
        )
    }
}

// ---------------------------------------------------------------- settings
object Store {
    private lateinit var sp: SharedPreferences
    private var ready = false
    var accent by mutableIntStateOf(0)
    var use24h by mutableStateOf(true)
    var showSeconds by mutableStateOf(true)
    var defaultSnooze by mutableIntStateOf(10)
    var silenceAfter by mutableIntStateOf(5)   // minutes, 0 = never
    var gradual by mutableStateOf(true)
    var defaultSound by mutableStateOf("")

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("mux_clock", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        use24h = sp.getBoolean("use24h", true)
        showSeconds = sp.getBoolean("seconds", true)
        defaultSnooze = sp.getInt("snooze", 10)
        silenceAfter = sp.getInt("silence", 5)
        gradual = sp.getBoolean("gradual", true)
        defaultSound = sp.getString("sound", "") ?: ""
        ready = true
    }

    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateUse24h(b: Boolean) { use24h = b; sp.edit().putBoolean("use24h", b).apply() }
    fun updateShowSeconds(b: Boolean) { showSeconds = b; sp.edit().putBoolean("seconds", b).apply() }
    fun updateSnooze(m: Int) { defaultSnooze = m; sp.edit().putInt("snooze", m).apply() }
    fun updateSilence(m: Int) { silenceAfter = m; sp.edit().putInt("silence", m).apply() }
    fun updateGradual(b: Boolean) { gradual = b; sp.edit().putBoolean("gradual", b).apply() }
    fun updateSound(s: String) { defaultSound = s; sp.edit().putString("sound", s).apply() }
}

// ---------------------------------------------------------------- data (alarms, timers, cities)
object Repo {
    private lateinit var sp: SharedPreferences
    private var ready = false
    var alarms by mutableStateOf<List<AlarmItem>>(emptyList())
    var timers by mutableStateOf<List<TimerItem>>(emptyList())
    var cities by mutableStateOf<List<String>>(emptyList())

    fun init(ctx: Context) {
        if (ready) return
        sp = ctx.applicationContext.getSharedPreferences("mux_clock_data", Context.MODE_PRIVATE)
        alarms = readList("alarms") { AlarmItem.from(it) }
        timers = readList("timers") { TimerItem.from(it) }
        cities = (sp.getString("cities", null) ?: "Europe/London,America/New_York,Asia/Tokyo").split(',').filter { it.isNotBlank() }
        ready = true
    }

    private fun <T> readList(key: String, f: (JSONObject) -> T): List<T> = try {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map { f(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun newId(): Int {
        val n = sp.getInt("nextId", 1)
        sp.edit().putInt("nextId", n + 1).apply()
        return n
    }

    private fun persistAlarms() { sp.edit().putString("alarms", JSONArray(alarms.map { it.toJson() }).toString()).apply() }
    private fun persistTimers() { sp.edit().putString("timers", JSONArray(timers.map { it.toJson() }).toString()).apply() }

    fun saveAlarm(a: AlarmItem) {
        alarms = if (alarms.any { it.id == a.id }) alarms.map { if (it.id == a.id) a else it } else alarms + a
        persistAlarms()
    }
    fun deleteAlarm(id: Int) { alarms = alarms.filter { it.id != id }; persistAlarms() }

    fun saveTimer(t: TimerItem) {
        timers = if (timers.any { it.id == t.id }) timers.map { if (it.id == t.id) t else it } else timers + t
        persistTimers()
    }
    fun deleteTimer(id: Int) { timers = timers.filter { it.id != id }; persistTimers() }

    fun addCity(id: String) {
        if (id !in cities) { cities = cities + id; sp.edit().putString("cities", cities.joinToString(",")).apply() }
    }
    fun removeCity(id: String) { cities = cities.filter { it != id }; sp.edit().putString("cities", cities.joinToString(",")).apply() }

    // stopwatch persistence
    fun swGet(): Triple<Boolean, Long, Long> = Triple(sp.getBoolean("sw_run", false), sp.getLong("sw_base", 0L), sp.getLong("sw_acc", 0L))
    fun swLaps(): List<Long> = (sp.getString("sw_laps", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
    fun swPut(run: Boolean, base: Long, acc: Long, laps: List<Long>) {
        sp.edit().putBoolean("sw_run", run).putLong("sw_base", base).putLong("sw_acc", acc).putString("sw_laps", laps.joinToString(",")).apply()
    }
}

// ---------------------------------------------------------------- formatting helpers
private val F24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val F12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val F24S = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
private val F12S = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)
private val FDATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val FDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

fun fmtT(z: ZonedDateTime): String = (if (Store.use24h) F24 else F12).format(z)
fun fmtT(hour: Int, minute: Int): String = fmtT(ZonedDateTime.now().withHour(hour).withMinute(minute))
fun fmtClock(z: ZonedDateTime): String = (if (Store.showSeconds) { if (Store.use24h) F24S else F12S } else { if (Store.use24h) F24 else F12 }).format(z)
fun fmtDate(z: ZonedDateTime): String = FDATE.format(z).uppercase()
fun fmtDay(z: ZonedDateTime): String = FDAY.format(z).uppercase()

fun cityName(id: String): String = id.substringAfterLast('/').replace('_', ' ')

fun zoneLabel(z: ZoneId): String {
    val off = z.rules.getOffset(java.time.Instant.now()).id.let { if (it == "Z") "+00:00" else it }
    return "${z.id.uppercase()}  GMT$off"
}

/** "7H 32M", "1D 2H", "5M" */
fun fmtIn(ms: Long): String {
    val d = Duration.ofMillis(ms.coerceAtLeast(0L))
    val days = d.toDays()
    val h = d.toHours() % 24
    val m = d.toMinutes() % 60
    return when {
        days > 0 -> "${days}D ${h}H"
        h > 0 -> "${h}H ${m}M"
        m > 0 -> "${m}M"
        else -> "<1M"
    }
}

private val DAY_NAMES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

fun daysText(mask: Int): String = when (mask) {
    0 -> "ONCE"
    127 -> "EVERY DAY"
    31 -> "WEEKDAYS"
    96 -> "WEEKENDS"
    else -> DAY_NAMES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" ")
}

fun dayName(i: Int): String = DAY_NAMES[i]

fun fmtTimer(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 999) / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun fmtWatch(ms: Long): String {
    val cs = ms / 10 % 100
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d.%02d".format(s / 3600, s / 60 % 60, s % 60, cs) else "%02d:%02d.%02d".format(s / 60, s % 60, cs)
}
