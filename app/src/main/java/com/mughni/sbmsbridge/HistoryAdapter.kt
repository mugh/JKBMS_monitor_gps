package com.mughni.sbmsbridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class HistoryAdapter(private val items: List<TripRecord>) :
    RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        
        val statDistance: View = view.findViewById(R.id.statDistance)
        val statDuration: View = view.findViewById(R.id.statDuration)
        val statEfficiency: View = view.findViewById(R.id.statEfficiency)
        val statSocUsed: View = view.findViewById(R.id.statSocUsed)
        val statAhUsed: View = view.findViewById(R.id.statAhUsed)
        val statAvgTemp: View = view.findViewById(R.id.statAvgTemp)

        fun bindStat(view: View, label: String, value: String) {
            view.findViewById<TextView>(R.id.tvLabel).text = label
            view.findViewById<TextView>(R.id.tvValue).text = value
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = item.date
        
        holder.bindStat(holder.statDistance, "DISTANCE", String.format(Locale.US, "%.2f km", item.distance))
        holder.bindStat(holder.statDuration, "DURATION", formatDuration(item.duration))
        holder.bindStat(holder.statEfficiency, "EFFICIENCY", String.format(Locale.US, "%.1f Wh/km", item.eff))
        holder.bindStat(holder.statSocUsed, "BATTERY (%)", String.format(Locale.US, "%.0f%%", item.socUsed))
        holder.bindStat(holder.statAhUsed, "BATTERY (Ah)", String.format(Locale.US, "%.2f Ah", Math.abs(item.ahUsed)))
        holder.bindStat(holder.statAvgTemp, "AVG TEMP", String.format(Locale.US, "%.1f °C", item.avgTemp))
    }

    override fun getItemCount(): Int = items.size

    private fun formatDuration(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        return if (h > 0) String.format(Locale.US, "%dh %dm %ds", h, m, s)
        else String.format(Locale.US, "%dm %ds", m, s)
    }
}
