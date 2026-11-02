package com.mux.clock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import java.time.ZonedDateTime

object ClockWidgets {
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, DigitalWidget::class.java)).takeIf { it.isNotEmpty() }?.let { DigitalWidget().onUpdate(ctx, mgr, it) }
        mgr.getAppWidgetIds(ComponentName(ctx, AnalogWidget::class.java)).takeIf { it.isNotEmpty() }?.let { AnalogWidget().onUpdate(ctx, mgr, it) }
        mgr.getAppWidgetIds(ComponentName(ctx, WorldWidget::class.java)).takeIf { it.isNotEmpty() }?.let { WorldWidget().onUpdate(ctx, mgr, it) }
    }

    fun nextAlarmText(): String {
        val n = Sched.next(Repo.alarms, ZonedDateTime.now()) ?: return "NO ALARM"
        return "ALARM ${fmtDay(n.second)} ${fmtT(n.second)}"
    }

    fun open(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun fmt(): String = if (Store.use24h) "HH:mm" else "h:mm"
    fun color(): Int = cMain.toArgb()

    /** Asks the launcher to add the widget (Android 8.0+). Returns false when the launcher does not support it. */
    fun pin(ctx: Context, cls: Class<*>): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null) else false
    }
}

class DigitalWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        Repo.init(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_digital)
        val f = ClockWidgets.fmt()
        v.setCharSequence(R.id.w_time, "setFormat12Hour", f)
        v.setCharSequence(R.id.w_time, "setFormat24Hour", f)
        v.setTextColor(R.id.w_time, ClockWidgets.color())
        v.setTextViewText(R.id.w_next, ClockWidgets.nextAlarmText())
        v.setOnClickPendingIntent(R.id.w_root, ClockWidgets.open(ctx))
        for (id in ids) mgr.updateAppWidget(id, v)
    }
}

class AnalogWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_analog)
        v.setOnClickPendingIntent(R.id.w_root, ClockWidgets.open(ctx))
        for (id in ids) mgr.updateAppWidget(id, v)
    }
}

class WorldWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        Repo.init(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_world)
        val f = ClockWidgets.fmt()
        v.setCharSequence(R.id.w_time, "setFormat12Hour", f)
        v.setCharSequence(R.id.w_time, "setFormat24Hour", f)
        v.setTextColor(R.id.w_time, ClockWidgets.color())
        v.setTextViewText(R.id.w_next, ClockWidgets.nextAlarmText())
        val rowFmt = if (Store.use24h) "HH:mm" else "h:mm a"
        val cities = Repo.cities.take(3)
        val rows = listOf(
            Triple(R.id.w_r1, R.id.w_c1, R.id.w_t1), Triple(R.id.w_r2, R.id.w_c2, R.id.w_t2), Triple(R.id.w_r3, R.id.w_c3, R.id.w_t3)
        )
        rows.forEachIndexed { i, (row, name, time) ->
            if (i < cities.size) {
                v.setViewVisibility(row, View.VISIBLE)
                v.setTextViewText(name, cityName(cities[i]).uppercase())
                v.setString(time, "setTimeZone", cities[i])
                v.setCharSequence(time, "setFormat12Hour", rowFmt)
                v.setCharSequence(time, "setFormat24Hour", rowFmt)
            } else {
                v.setViewVisibility(row, View.GONE)
            }
        }
        v.setOnClickPendingIntent(R.id.w_root, ClockWidgets.open(ctx))
        for (id in ids) mgr.updateAppWidget(id, v)
    }
}
