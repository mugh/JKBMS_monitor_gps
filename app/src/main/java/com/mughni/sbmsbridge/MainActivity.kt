package com.mughni.sbmsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvClockTime: TextView
    private lateinit var tvClockDate: TextView
    private lateinit var tvBleStatus: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var btnTrip: MaterialButton
    private lateinit var pbSocCircle: ProgressBar
    private lateinit var tvSocValue: TextView
    private lateinit var tvToFull: TextView
    
    private lateinit var tvVoltage: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvCapacity: TextView
    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvEfficiency: TextView
    private lateinit var tvOdometer: TextView
    private lateinit var tvRangeValue: TextView
    private lateinit var tvTripTime: TextView
    private lateinit var tvEnergy: TextView

    private lateinit var tvSpeed: TextView
    private lateinit var pbSpeed: ProgressBar

    private var latestBmsData: JSONObject? = null
    private var lastRawData: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("sbms_bridge", MODE_PRIVATE) }

    private var tripData = TripData()
    private var lastLocation: Location? = null
    private var tripService: TripService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TripService.LocalBinder
            tripService = binder.getService()
            isBound = true
            
            tripService?.setUiCallback { data, bms, loc ->
                runOnUiThread {
                    tripData = data
                    latestBmsData = bms
                    lastRawData = bms?.optString("rawText", "") ?: ""
                    lastLocation = loc
                    updateUi()
                }
            }
            
            val savedMac = prefs.getString("bms_mac", null)
            if (savedMac != null && isValidMac(savedMac)) {
                checkBluetoothPermissionThenConnect(savedMac)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            tripService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        applyAlwaysOn(prefs.getBoolean("always_on", true))
        startClockUpdates()
        checkLocationPermission()
        checkNotificationPermission()
        
        val intent = Intent(this, TripService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            tripService?.setUiCallback(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initViews() {
        tvClockTime = findViewById(R.id.tvClockTime)
        tvClockDate = findViewById(R.id.tvClockDate)
        tvBleStatus = findViewById(R.id.tvBleStatus)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        btnTrip = findViewById(R.id.btnTrip)
        pbSocCircle = findViewById(R.id.pbSocCircle)
        tvSocValue = findViewById(R.id.tvSocValue)
        tvToFull = findViewById<TextView>(R.id.tvToFull)
        tvSpeed = findViewById(R.id.tvSpeed)
        pbSpeed = findViewById(R.id.pbSpeed)
        tvRangeValue = findViewById(R.id.tvRangeValue)

        tvVoltage = setupMetric(R.id.metricVoltage, R.string.pack_voltage)
        tvCurrent = setupMetric(R.id.metricCurrent, R.string.current)
        tvPower = setupMetric(R.id.metricPower, R.string.power)
        tvCapacity = setupMetric(R.id.metricCapacity, R.string.remaining_capacity)
        
        val metricTemp1: View = findViewById(R.id.metricTemp1)
        metricTemp1.findViewById<TextView>(R.id.tvLabel).text = "TEMPERATURE T1"
        tvTemp1 = metricTemp1.findViewById(R.id.tvValue)

        val metricTemp2: View = findViewById(R.id.metricTemp2)
        metricTemp2.findViewById<TextView>(R.id.tvLabel).text = "TEMPERATURE T2"
        tvTemp2 = metricTemp2.findViewById(R.id.tvValue)

        tvEfficiency = setupMetric(R.id.metricEfficiency, R.string.avg_efficiency)
        tvOdometer = setupMetric(R.id.metricOdometer, R.string.trip_distance)
        tvTripTime = setupMetric(R.id.metricTripTime, R.string.trip_time)
        tvEnergy = setupMetric(R.id.metricEnergy, R.string.energy_used)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnShowHistory).setOnClickListener { showHistoryDialog() }
        
        btnTrip.setOnClickListener {
            val running = tripService?.isTripRunning ?: false
            if (!running) startTrip() else finishTrip()
        }
    }

    private fun setupMetric(includeId: Int, labelRes: Int): TextView {
        val view = findViewById<View>(includeId)
        view.findViewById<TextView>(R.id.tvLabel).text = getString(labelRes)
        return view.findViewById(R.id.tvValue)
    }

    private fun startClockUpdates() {
        val clockRunnable = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance().time
                tvClockTime.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
                tvClockDate.text = SimpleDateFormat("EEEE, d MMM", Locale.US).format(now).uppercase()

                val running = tripService?.isTripRunning ?: false
                if (running) {
                    val startTime = tripService?.tripData?.startTime ?: 0L
                    if (startTime != 0L) {
                        val diff = System.currentTimeMillis() - startTime
                        val h = diff / 3600000
                        val m = (diff % 3600000) / 60000
                        val s = (diff % 60000) / 1000
                        tvTripTime.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                    }
                } else {
                    tvTripTime.text = "00:00:00"
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(clockRunnable)
    }

    private fun updateUi() {
        updateBmsUi()
        updateTripUiState()
        updateLocationUi()
        updateEfficiencyUi()
    }

    private fun updateBmsUi() {
        val d = latestBmsData ?: return
        lastRawData = d.optString("rawText", "")
        
        tvBleStatus.text = "BLE: ACTIVE"
        tvBleStatus.setTextColor(ContextCompat.getColor(this, R.color.green))

        val soc = d.optInt("soc", -1)
        if (soc != -1) {
            tvSocValue.text = "$soc%"
            pbSocCircle.progress = soc
        }

        val current = d.optDouble("current", Double.NaN)
        val isCharging = !current.isNaN() && current > 0.05
        
        if (isCharging) {
            pbSocCircle.progressDrawable.setTint(ContextCompat.getColor(this, R.color.green))
            tvSocValue.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            val color = if (soc < 20) R.color.red else if (soc < 50) R.color.amber else R.color.cyan
            pbSocCircle.progressDrawable.setTint(ContextCompat.getColor(this, color))
            tvSocValue.setTextColor(ContextCompat.getColor(this, if (soc < 20) R.color.red else R.color.cyan))
        }

        val voltage = d.optDouble("voltage", Double.NaN)
        tvVoltage.text = if (voltage.isNaN()) "-- V" else String.format(Locale.US, "%.1f V", voltage)
        
        tvCurrent.text = if (current.isNaN()) "-- A" else String.format(Locale.US, "%.1f A", current)

        val power = d.optDouble("power", Double.NaN)
        tvPower.text = if (power.isNaN()) "-- W" else String.format(Locale.US, "%.0f W", Math.abs(power))

        val remainingAh = d.optDouble("remainingAh", Double.NaN)
        tvCapacity.text = if (remainingAh.isNaN()) "-- Ah" else String.format(Locale.US, "%.1f Ah", remainingAh)

        val t1 = d.optDouble("temp1", Double.NaN)
        val t2 = d.optDouble("temp2", Double.NaN)
        tvTemp1.text = if (t1.isNaN()) "-- °C" else String.format(Locale.US, "%.1f °C", t1)
        tvTemp2.text = if (t2.isNaN()) "-- °C" else String.format(Locale.US, "%.1f °C", t2)

        if (isCharging && !remainingAh.isNaN()) {
            val fullAh = d.optDouble("fullCapacityAh", Double.NaN)
            if (!fullAh.isNaN() && fullAh - remainingAh > 0.1) {
                val hours = (fullAh - remainingAh) / current
                val h = hours.toInt()
                val m = ((hours - h) * 60).toInt()
                tvToFull.text = if (h > 0) "${h}h ${m}m TO FULL" else "${m}m TO FULL"
                tvToFull.visibility = View.VISIBLE
            } else tvToFull.visibility = View.GONE
        } else tvToFull.visibility = View.GONE
    }

    private fun updateTripUiState() {
        val running = tripService?.isTripRunning ?: false
        if (running) {
            btnTrip.text = getString(R.string.finish_trip)
            btnTrip.strokeColor = ContextCompat.getColorStateList(this, R.color.red)
            btnTrip.setTextColor(ContextCompat.getColor(this, R.color.red))
            tvGpsStatus.text = "GPS: ACTIVE"
            tvGpsStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan))
        } else {
            btnTrip.text = getString(R.string.start_trip)
            btnTrip.strokeColor = ContextCompat.getColorStateList(this, R.color.cyan)
            btnTrip.setTextColor(ContextCompat.getColor(this, R.color.cyan))
            tvGpsStatus.text = "GPS: OFF"
            tvGpsStatus.setTextColor(ContextCompat.getColor(this, R.color.muted))
        }
    }

    private fun updateLocationUi() {
        val loc = lastLocation
        val speedKmh = if (loc != null) loc.speed * 3.6 else 0.0
        tvSpeed.text = String.format(Locale.US, "%.0f", speedKmh)
        pbSpeed.progress = speedKmh.toInt()
        tvOdometer.text = String.format(Locale.US, "%.2f KM", tripData.distanceKm)
    }

    private fun updateEfficiencyUi() {
        tvEnergy.text = String.format(Locale.US, "%.1f Wh", tripData.energyWh)
        val eff = if (tripData.distanceKm > 0.05) tripData.energyWh / tripData.distanceKm else null
        tvEfficiency.text = if (eff != null) String.format(Locale.US, "%.1f Wh/km", eff) else "-- Wh/km"
        
        latestBmsData?.let { d ->
            val remainingAh = d.optDouble("remainingAh", Double.NaN)
            val voltage = d.optDouble("voltage", Double.NaN)
            if (eff != null && eff > 0.01 && !remainingAh.isNaN() && !voltage.isNaN()) {
                val remainingWh = remainingAh * voltage
                tvRangeValue.text = String.format(Locale.US, "%.1f", remainingWh / eff)
            } else {
                tvRangeValue.text = "--"
            }
        }
    }

    private fun startTrip() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }
        tripService?.startTripTracking()
    }

    private fun finishTrip() {
        tripService?.stopTripTracking()
        saveTripRecord()
    }

    private fun saveTripRecord() {
        val currentSoc = latestBmsData?.optDouble("soc", Double.NaN) ?: Double.NaN
        val currentAh = latestBmsData?.optDouble("remainingAh", Double.NaN) ?: Double.NaN
        
        val record = TripRecord(
            date = SimpleDateFormat("MM/dd/yyyy, HH:mm:ss", Locale.US).format(Date()),
            distance = tripData.distanceKm,
            duration = tripData.durationMs,
            socUsed = if (tripData.startSoc != null && !currentSoc.isNaN()) tripData.startSoc!! - currentSoc else 0.0,
            ahUsed = if (tripData.startAh != null && !currentAh.isNaN()) tripData.startAh!! - currentAh else 0.0,
            eff = if (tripData.distanceKm > 0.1) tripData.energyWh / tripData.distanceKm else 0.0,
            avgTemp = if (tripData.tempCount > 0) tripData.tempSum / tripData.tempCount else 0.0,
            maxTemp = tripData.maxTemp
        )
        
        val historyStr = prefs.getString("trip_history", "[]")
        val history = JSONArray(historyStr)
        val newArray = JSONArray()
        newArray.put(record.toJsonObject())
        for (i in 0 until Math.min(history.length(), 49)) {
            newArray.put(history.get(i))
        }
        prefs.edit().putString("trip_history", newArray.toString()).apply()
    }

    private fun showHistoryDialog() {
        val historyStr = prefs.getString("trip_history", "[]")
        val historyArray = JSONArray(historyStr)
        val items = mutableListOf<TripRecord>()
        for (i in 0 until historyArray.length()) {
            items.add(TripRecord.fromJsonObject(historyArray.getJSONObject(i)))
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val rvHistory = dialogView.findViewById<RecyclerView>(R.id.rvHistory)
        val btnExport = dialogView.findViewById<MaterialButton>(R.id.btnExport)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)

        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = HistoryAdapter(items)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create()

        btnExport.setOnClickListener { exportHistory(items) }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun exportHistory(items: List<TripRecord>) {
        if (items.isEmpty()) return
        val csv = StringBuilder("Date,Distance_km,Duration,Efficiency_Whkm,Battery_Percent,Battery_Ah,Temp_Avg,Temp_Max\n")
        items.forEach { h ->
            val hms = String.format(Locale.US, "%02d:%02d:%02d", h.duration/3600000, (h.duration%3600000)/60000, (h.duration%60000)/1000)
            csv.append(h.date + "," + String.format(Locale.US, "%.2f", h.distance) + "," + hms + "," + String.format(Locale.US, "%.1f", h.eff) + "," + String.format(Locale.US, "%.0f", h.socUsed) + "," + String.format(Locale.US, "%.2f", Math.abs(h.ahUsed)) + "," + String.format(Locale.US, "%.1f", h.avgTemp) + "," + String.format(Locale.US, "%.1f", h.maxTemp) + "\n")
        }
        val filename = "trip_log_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".csv"
        saveCsv(csv.toString(), filename)
    }

    private fun saveCsv(content: String, filename: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues()
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            } else null

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Toast.makeText(this, "Exported to Downloads", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAlwaysOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val macInput = dialogView.findViewById<EditText>(R.id.macInput)
        val cbAlwaysOn = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.cbAlwaysOn)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)
        val tvRawData = dialogView.findViewById<TextView>(R.id.tvRawData)

        macInput.setText(prefs.getString("bms_mac", ""))
        cbAlwaysOn.isChecked = prefs.getBoolean("always_on", true)
        tvRawData.text = if (lastRawData.isEmpty()) getString(R.string.no_data_yet) else lastRawData

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSave.setOnClickListener {
            val mac = macInput.text.toString().trim().uppercase()
            if (!isValidMac(mac)) {
                Toast.makeText(this, "Invalid MAC", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("bms_mac", mac).putBoolean("always_on", cbAlwaysOn.isChecked).apply()
            applyAlwaysOn(cbAlwaysOn.isChecked)
            reconnect(mac)
            dialog.dismiss()
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun isValidMac(mac: String) = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)
    private fun reconnect(mac: String) {
        tripService?.disconnectBle()
        checkBluetoothPermissionThenConnect(mac)
    }

    private fun checkBluetoothPermissionThenConnect(mac: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
                return
            }
        }
        tripService?.connectBle(mac)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prefs.getString("bms_mac", null)?.let { tripService?.connectBle(it) }
        } else if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            tripService?.startTripTracking()
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
    }
}
