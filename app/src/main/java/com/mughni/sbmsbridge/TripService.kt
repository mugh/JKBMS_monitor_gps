package com.mughni.sbmsbridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.*

class TripService : Service(), LocationListener {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private var bleConnector: BleConnector? = null
    
    private val prefs by lazy { getSharedPreferences("sbms_bridge", MODE_PRIVATE) }
    
    var isTripRunning = false
        private set
    var tripData = TripData()
        private set
    var latestBmsData: JSONObject? = null
        private set
    var lastLocation: Location? = null
        private set

    private var uiCallback: ((TripData, JSONObject?, Location?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    inner class LocalBinder : Binder() {
        fun getService(): TripService = this@TripService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
        
        if (prefs.getBoolean("is_trip_active", false)) {
            val dataStr = prefs.getString("active_trip_data", null)
            if (dataStr != null) {
                try {
                    tripData = TripData.fromJsonObject(JSONObject(dataStr))
                    startTripTracking(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "trip_service",
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, "trip_service")
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(R.drawable.status_dot_gps)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun startTripTracking(resuming: Boolean = false) {
        if (!resuming) {
            tripData = TripData()
            tripData.startTime = System.currentTimeMillis()
            tripData.lastPowerSampleTime = System.currentTimeMillis()
            
            latestBmsData?.let { d ->
                val curSoc = d.optDouble("soc", Double.NaN)
                tripData.startSoc = if (curSoc.isNaN()) null else curSoc
                val curAh = d.optDouble("remainingAh", Double.NaN)
                tripData.startAh = if (curAh.isNaN()) null else curAh
            }
        }
        
        isTripRunning = true
        lastLocation = null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, getNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, getNotification())
        }
        
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        
        prefs.edit().putBoolean("is_trip_active", true).apply()
        saveInProgressTrip()
        
        handler.post(saveRunnable)
        notifyUi()
    }

    fun stopTripTracking() {
        locationManager.removeUpdates(this)
        isTripRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        prefs.edit().putBoolean("is_trip_active", false).remove("active_trip_data").apply()
        handler.removeCallbacks(saveRunnable)
        notifyUi()
    }
    
    fun connectBle(mac: String) {
        bleConnector?.disconnect()
        bleConnector = BleConnector(this, mac) { json ->
            try {
                val d = JSONObject(json)
                latestBmsData = d
                if (isTripRunning) {
                    if (tripData.startSoc == null) {
                        val soc = d.optDouble("soc", Double.NaN)
                        if (!soc.isNaN()) tripData.startSoc = soc
                    }
                    if (tripData.startAh == null) {
                        val ah = d.optDouble("remainingAh", Double.NaN)
                        if (!ah.isNaN()) tripData.startAh = ah
                    }
                    accumulateEnergy(d)
                }
                notifyUi()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bleConnector?.connect()
    }
    
    fun disconnectBle() {
        bleConnector?.disconnect()
    }

    private fun accumulateEnergy(d: JSONObject) {
        val now = System.currentTimeMillis()
        val current = d.optDouble("current", Double.NaN)
        val power = d.optDouble("power", Double.NaN)
        
        if (tripData.lastPowerSampleTime != 0L && !current.isNaN() && !power.isNaN()) {
            val dtH = (now - tripData.lastPowerSampleTime).toDouble() / 3600000.0
            val dischargeW = if (current < 0) Math.abs(power) else 0.0
            tripData.energyWh += dischargeW * dtH
        }
        
        val t1 = d.optDouble("temp1", Double.NaN)
        val t2 = d.optDouble("temp2", Double.NaN)
        if (!t1.isNaN()) {
            val t = if (!t2.isNaN()) (t1 + t2) / 2.0 else t1
            tripData.tempSum += t
            tripData.tempCount++
            if (t > tripData.maxTemp) tripData.maxTemp = t
        }
        
        tripData.lastPowerSampleTime = now
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy > 25) return
        
        if (lastLocation != null) {
            val dKm = lastLocation!!.distanceTo(location).toDouble() / 1000.0
            val dtS = (location.time - lastLocation!!.time).toDouble() / 1000.0
            val impliedSpeedKmh = if (dtS > 0) (dKm / dtS) * 3600.0 else 0.0
            val noiseFloorKm = location.accuracy.toDouble() / 1000.0
            
            if (dKm < 0.3 && impliedSpeedKmh < 70 && dKm > noiseFloorKm) {
                tripData.distanceKm += dKm
            }
        }
        lastLocation = location
        notifyUi()
    }

    private fun notifyUi() {
        handler.post {
            if (isTripRunning) {
                tripData.durationMs = System.currentTimeMillis() - tripData.startTime
            }
            uiCallback?.invoke(tripData, latestBmsData, lastLocation)
        }
    }

    fun setUiCallback(callback: ((TripData, JSONObject?, Location?) -> Unit)?) {
        uiCallback = callback
        notifyUi()
    }

    private val saveRunnable = object : Runnable {
        override fun run() {
            if (isTripRunning) {
                saveInProgressTrip()
                handler.postDelayed(this, 10000)
            }
        }
    }

    private fun saveInProgressTrip() {
        if (isTripRunning) {
            prefs.edit().putString("active_trip_data", tripData.toJsonObject().toString()).apply()
        }
    }

    override fun onDestroy() {
        saveInProgressTrip()
        bleConnector?.disconnect()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
