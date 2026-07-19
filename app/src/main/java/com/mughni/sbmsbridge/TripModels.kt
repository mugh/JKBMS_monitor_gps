package com.mughni.sbmsbridge

import org.json.JSONArray
import org.json.JSONObject

data class TripRecord(
    val date: String,
    val distance: Double,
    val duration: Long,
    val socUsed: Double,
    val ahUsed: Double,
    val eff: Double,
    val avgTemp: Double,
    val maxTemp: Double
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("date", date)
        obj.put("distance", distance)
        obj.put("duration", duration)
        obj.put("socUsed", socUsed)
        obj.put("ahUsed", ahUsed)
        obj.put("eff", eff)
        obj.put("avgTemp", avgTemp)
        obj.put("maxTemp", maxTemp)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): TripRecord {
            return TripRecord(
                date = obj.getString("date"),
                distance = obj.getDouble("distance"),
                duration = obj.getLong("duration"),
                socUsed = obj.getDouble("socUsed"),
                ahUsed = obj.getDouble("ahUsed"),
                eff = obj.getDouble("eff"),
                avgTemp = obj.optDouble("avgTemp", 0.0),
                maxTemp = obj.optDouble("maxTemp", 0.0)
            )
        }
    }
}

data class TripData(
    var distanceKm: Double = 0.0,
    var energyWh: Double = 0.0,
    var lastPowerSampleTime: Long = 0,
    var tempSum: Double = 0.0,
    var tempCount: Int = 0,
    var maxTemp: Double = 0.0,
    var durationMs: Long = 0,
    var startTime: Long = 0,
    var startSoc: Double? = null,
    var startAh: Double? = null
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("distanceKm", distanceKm)
        obj.put("energyWh", energyWh)
        obj.put("lastPowerSampleTime", lastPowerSampleTime)
        obj.put("tempSum", tempSum)
        obj.put("tempCount", tempCount)
        obj.put("maxTemp", maxTemp)
        obj.put("durationMs", durationMs)
        obj.put("startTime", startTime)
        obj.put("startSoc", startSoc ?: JSONObject.NULL)
        obj.put("startAh", startAh ?: JSONObject.NULL)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): TripData {
            val data = TripData()
            data.distanceKm = obj.optDouble("distanceKm", 0.0)
            data.energyWh = obj.optDouble("energyWh", 0.0)
            data.lastPowerSampleTime = obj.optLong("lastPowerSampleTime", 0)
            data.tempSum = obj.optDouble("tempSum", 0.0)
            data.tempCount = obj.optInt("tempCount", 0)
            data.maxTemp = obj.optDouble("maxTemp", 0.0)
            data.durationMs = obj.optLong("durationMs", 0)
            data.startTime = obj.optLong("startTime", 0)
            data.startSoc = if (obj.isNull("startSoc")) null else obj.optDouble("startSoc")
            data.startAh = if (obj.isNull("startAh")) null else obj.optDouble("startAh")
            return data
        }
    }
}
