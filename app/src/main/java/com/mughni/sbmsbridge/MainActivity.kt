package com.mughni.sbmsbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var bleConnector: BleConnector? = null
    private var lastRawData: String = "Belum ada data..."

    private val prefs by lazy { getSharedPreferences("sbms_bridge", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyAlwaysOn(prefs.getBoolean("always_on", true))

        webView = findViewById(R.id.webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun openSettings() {
                runOnUiThread { showSettingsDialog() }
            }

            @android.webkit.JavascriptInterface
            fun saveCsv(content: String, filename: String) {
                runOnUiThread {
                    try {
                        val resolver = contentResolver
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            }
                        }

                        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        } else {
                            // Fallback untuk versi lama atau biarkan download manager yang handle jika perlu
                            // Namun untuk kemudahan di versi modern:
                            null
                        }

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                            Toast.makeText(this@MainActivity, "Berhasil! Cek folder Downloads", Toast.LENGTH_LONG).show()
                        } else {
                            // Jika gagal via MediaStore (versi lama), kita pakai Share Intent saja agar user bisa pilih simpan dimana
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(android.content.Intent.EXTRA_SUBJECT, filename)
                                putExtra(android.content.Intent.EXTRA_TEXT, content)
                            }
                            startActivity(android.content.Intent.createChooser(intent, "Simpan/Bagikan CSV"))
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
        webView.loadUrl("file:///android_asset/dashboard.html")

        checkLocationPermission()

        // auto-connect on launch if we already have a saved MAC
        val savedMac = prefs.getString("bms_mac", null)
        if (savedMac != null && isValidMac(savedMac)) {
            checkBluetoothPermissionThenConnect(savedMac)
        }
    }

    private fun applyAlwaysOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val macInput = dialogView.findViewById<EditText>(R.id.macInput)
        val cbAlwaysOn = dialogView.findViewById<CheckBox>(R.id.cbAlwaysOn)
        val tvRawData = dialogView.findViewById<TextView>(R.id.tvRawData)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)

        val savedMac = prefs.getString("bms_mac", "")
        macInput.setText(savedMac)
        cbAlwaysOn.isChecked = prefs.getBoolean("always_on", true)
        tvRawData.text = lastRawData

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            val mac = macInput.text.toString().trim().uppercase()
            if (!isValidMac(mac)) {
                Toast.makeText(this, "Format MAC gak valid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val alwaysOn = cbAlwaysOn.isChecked
            prefs.edit()
                .putString("bms_mac", mac)
                .putBoolean("always_on", alwaysOn)
                .apply()

            applyAlwaysOn(alwaysOn)
            reconnect(mac)
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun isValidMac(mac: String): Boolean {
        return Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(mac)
    }

    private fun reconnect(mac: String) {
        bleConnector?.disconnect()
        checkBluetoothPermissionThenConnect(mac)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleConnector?.disconnect()
    }

    private fun pushToWebView(json: String) {
        try {
            val obj = JSONObject(json)
            lastRawData = obj.optString("rawText", "No raw data")
        } catch (e: Exception) {}

        runOnUiThread {
            webView.evaluateJavascript("window.onAccessibilityData(${jsonAsJsLiteral(json)});", null)
        }
    }

    // ============ BLE (nRF Connect-style: connect + subscribe, no login command) ============

    private fun checkBluetoothPermissionThenConnect(mac: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                pendingMac = mac
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101
                )
                return
            }
        }
        startBleConnection(mac)
    }

    private var pendingMac: String? = null

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingMac?.let { startBleConnection(it) }
        }
    }

    private fun startBleConnection(mac: String) {
        bleConnector = BleConnector(this, mac) { json ->
            pushToWebView(json)
        }
        bleConnector?.connect()
    }

    // ============ misc ============

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100
            )
        }
    }

    private fun jsonAsJsLiteral(json: String): String {
        return "'" + json.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
    }
}