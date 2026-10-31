package na.gideon.vehiclelog

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        TripStore.channels(this)
        TripStore.log(this, "App opened (v${packageManager.getPackageInfo(packageName, 0).versionName}, Android ${Build.VERSION.RELEASE}/API ${Build.VERSION.SDK_INT})")
        handleNative(intent)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "geo") { startActivity(Intent(Intent.ACTION_VIEW, u)); return true }
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try { startActivityForResult(i, 42); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        web.loadUrl("file:///android_asset/www/index.html" + hashFor(intent))
        if (getSharedPreferences("app", 0).getBoolean("asked", false).not()) { getSharedPreferences("app", 0).edit().putBoolean("asked", true).apply(); requestPerms() }
    }

    private fun hashFor(i: Intent?): String {
        val o = i?.getStringExtra("open") ?: return ""
        return when { o.startsWith("trip:") -> "#open-" + o.substring(5); o == "resume" -> "#tab-trips"; o == "more" -> "#tab-set"; else -> "#tab-trips" }
    }
    private fun handleNative(i: Intent?) { if (i?.getStringExtra("open") == "resume") { TripStore.log(this, "Start from notification tap: " + TripStore.start(this)) } }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleNative(i); val h = hashFor(i); if (h.isNotEmpty()) web.evaluateJavascript("location.hash='${h.substring(1)}'", null) }
    override fun onResume() { super.onResume(); web.evaluateJavascript("window.natSync&&natSync()", null) }
    @Deprecated("") override fun onBackPressed() { web.evaluateJavascript("(function(){var d=document.getElementById('dlg');if(d&&d.open){d.close();return 1}return 0})()") { if (it == "0") @Suppress("DEPRECATION") super.onBackPressed() } }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 42) { fileCb?.onReceiveValue(if (res == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null); fileCb = null }
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun requestPerms() {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) l.add("android.permission.BLUETOOTH_CONNECT")
        if (Build.VERSION.SDK_INT >= 33) l.add("android.permission.POST_NOTIFICATIONS")
        val need = l.filter { !has(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else askBackground()
    }
    private fun askBackground() {
        if (Build.VERSION.SDK_INT >= 29 && has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            android.app.AlertDialog.Builder(this).setTitle("Location \"Allow all the time\"")
                .setMessage("To record trips with the screen off and start them from your car's Bluetooth, choose \"Allow all the time\" on the next screen.")
                .setPositiveButton("Continue") { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) }
                .setNegativeButton("Not now", null).show()
        } else askBattery()
    }
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try { @SuppressLint("BatteryLife") val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")); startActivity(i) } catch (e: Exception) {}
        }
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        TripStore.log(this, "Permissions: " + Bridge().status())
        if (rc == 1) askBackground() else if (rc == 2) askBattery()
        web.evaluateJavascript("window.natRender&&natRender()", null)
    }

    inner class Bridge {
        @JavascriptInterface fun sync(): String = TripStore.syncJson(this@MainActivity)
        @JavascriptInterface fun ack(json: String) = TripStore.ack(this@MainActivity, json)
        @JavascriptInterface fun cmd(c: String): String = when (c) {
            "start" -> TripStore.start(this@MainActivity)
            "stop" -> TripStore.pause(this@MainActivity, TripStore.state(this@MainActivity).optJSONObject("lastFix"))
            "home" -> TripStore.close(this@MainActivity)
            "end" -> TripStore.endAuto(this@MainActivity, interactive = true)
            else -> "?"
        }
        @JavascriptInterface fun setHome(json: String) { val s = TripStore.state(this@MainActivity); if (json == "null" || json.isEmpty()) s.remove("home") else s.put("home", JSONObject(json)); TripStore.persist(this@MainActivity) }
        @JavascriptInterface fun setCar(addr: String) { TripStore.state(this@MainActivity).put("car", addr); TripStore.persist(this@MainActivity) }
        @JavascriptInterface fun paired(): String {
            val a = JSONArray()
            try {
                if (Build.VERSION.SDK_INT >= 31 && !has("android.permission.BLUETOOTH_CONNECT")) return "{\"error\":\"Bluetooth permission not granted\"}"
                val bt = getSystemService(BluetoothManager::class.java)?.adapter ?: return "{\"error\":\"No Bluetooth on this device\"}"
                for (d in bt.bondedDevices) a.put(JSONObject().put("name", d.name ?: d.address).put("addr", d.address))
            } catch (e: SecurityException) { return "{\"error\":\"Bluetooth permission not granted\"}" }
            return JSONObject().put("devices", a).toString()
        }
        @JavascriptInterface fun log(): String = TripStore.logJson(this@MainActivity)
        @JavascriptInterface fun logMsg(m: String) = TripStore.log(this@MainActivity, "web: " + m)
        @JavascriptInterface fun getCar(): String = TripStore.state(this@MainActivity).optString("car", "")
        @JavascriptInterface fun shareLog() { runOnUiThread {
            val txt = "Vehicle Log diagnostics\nStatus: " + status() + "\nCar: " + getCar() + "\nTrip: " + (TripStore.running(this@MainActivity)?.let { "running, paused=" + it.optBoolean("paused") + ", km=" + it.optDouble("gpsKm") + ", points=" + it.optJSONArray("track")?.length() } ?: "none") + "\n\n" + TripStore.logText(this@MainActivity)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Vehicle Log diagnostics").putExtra(Intent.EXTRA_TEXT, txt), "Share log")) } }
        @JavascriptInterface fun status(): String {
            val pm = getSystemService(PowerManager::class.java)
            return JSONObject().put("fine", has(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("background", Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                .put("bt", Build.VERSION.SDK_INT < 31 || has("android.permission.BLUETOOTH_CONNECT"))
                .put("notif", Build.VERSION.SDK_INT < 33 || has("android.permission.POST_NOTIFICATIONS"))
                .put("battery", pm.isIgnoringBatteryOptimizations(packageName))
                .put("gpsOn", try { getSystemService(android.location.LocationManager::class.java).isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false })
                .put("fixes", TripStore.fixCount).toString()
        }
        @JavascriptInterface fun requestPerms() { runOnUiThread { this@MainActivity.requestPerms() } }
        @JavascriptInterface fun openSettings() { runOnUiThread { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
        @JavascriptInterface fun battery() { runOnUiThread { askBattery() } }
        @JavascriptInterface fun saveFile(name: String, mime: String, text: String): String {
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime) }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return "Save failed"
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                    "Saved to Downloads/$name"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS); val f = File(dir, name); f.writeText(text); "Saved to ${f.absolutePath}"
                }
            } catch (e: Exception) { "Save failed: ${e.message}" }
        }
    }
}
