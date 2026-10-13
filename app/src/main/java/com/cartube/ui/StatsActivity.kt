package com.cartube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.cartube.R
import com.cartube.data.WatchStats
import com.cartube.databinding.ActivityStatsBinding
import com.cartube.databinding.ItemStatBarBinding
import com.cartube.notify.Reminders
import java.time.format.TextStyle
import java.util.Locale

/** How much you have watched: today, this week, the last two weeks and top channels. */
class StatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyAmoledIfEnabled()
        val binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.toolbar.setNavigationOnClickListener { finish() }

        val week = WatchStats.lastDays(7)
        val twoWeeks = WatchStats.lastDays(14)
        binding.today.text = Reminders.formatMinutes(WatchStats.todayMs())
        binding.week.text = Reminders.formatMinutes(week.sumOf { it.second })
        binding.average.text = Reminders.formatMinutes(week.sumOf { it.second } / 7)

        val swedish = Locale("sv")
        val max = twoWeeks.maxOf { it.second }.coerceAtLeast(1)
        for ((date, ms) in twoWeeks.reversed()) {
            val row = ItemStatBarBinding.inflate(LayoutInflater.from(this), binding.days, false)
            row.label.text = "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, swedish)} ${date.dayOfMonth}/${date.monthValue}"
            row.value.text = Reminders.formatMinutes(ms)
            row.bar.max = 1000
            row.bar.progress = (ms * 1000 / max).toInt()
            binding.days.addView(row.root)
        }

        val channels = WatchStats.topChannels(5)
        val channelMax = channels.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        for ((name, ms) in channels) {
            val row = ItemStatBarBinding.inflate(LayoutInflater.from(this), binding.channels, false)
            row.label.text = name
            row.value.text = Reminders.formatMinutes(ms)
            row.bar.max = 1000
            row.bar.progress = (ms * 1000 / channelMax).toInt()
            binding.channels.addView(row.root)
        }
        if (channels.isEmpty()) {
            binding.channels.addView(TextView(this).apply { setText(R.string.stats_none) })
        }
    }
}
