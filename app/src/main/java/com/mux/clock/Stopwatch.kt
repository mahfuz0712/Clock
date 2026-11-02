package com.mux.clock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Stopwatch that keeps counting while the app is closed (uses the phone's monotonic clock). */
object Stopwatch {
    var running by mutableStateOf(false)
    var laps by mutableStateOf<List<Long>>(emptyList())   // cumulative time at each lap
    private var base = 0L
    private var acc = 0L

    fun load() {
        val (run, b, a) = Repo.swGet()
        acc = a
        val now = SystemClock.elapsedRealtime()
        if (run && b in 1..now) { base = b; running = true } else running = false
        laps = Repo.swLaps()
    }

    fun elapsed(): Long = acc + if (running) SystemClock.elapsedRealtime() - base else 0L

    private fun persist() { Repo.swPut(running, base, acc, laps) }

    fun start() {
        if (running) return
        base = SystemClock.elapsedRealtime()
        running = true
        persist()
    }

    fun stop() {
        if (!running) return
        acc += SystemClock.elapsedRealtime() - base
        running = false
        persist()
    }

    fun lap() {
        if (!running) return
        laps = laps + elapsed()
        persist()
    }

    fun reset() {
        running = false
        acc = 0L
        base = 0L
        laps = emptyList()
        persist()
    }
}
