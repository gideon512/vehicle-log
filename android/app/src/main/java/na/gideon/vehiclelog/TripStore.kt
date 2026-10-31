package na.gideon.vehiclelog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Native trip state machine. Mirrors the web app's model (legs, stops, track, gpsKm). */
object TripStore {
    private const val PREFS = "trip"
    const val CH_TRACK = "tracking"
    const val CH_PROMPT = "prompt"
    const val NOTIF_TRACK = 1
    private var st: JSONObject? = null
    private var lastPersist = 0L
    private var lastRec: DoubleArray? = null // t, lat, lon
    private var lastRaw: DoubleArray? = null
    private var lastKmPos: DoubleArray? = null

    @Synchronized fun state(ctx: Context): JSONObject {
        st?.let { return it }
        val s = ctx.getSharedPreferences(PREFS, 0).getString("state", null)
        val o = try { if (s != null) JSONObject(s) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (!o.has("finished")) o.put("finished", JSONArray())
        if (!o.has("uses")) o.put("uses", JSONObject())
        st = o; return o
    }
    @Synchronized fun persist(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString("state", state(ctx).toString()).apply(); lastPersist = System.currentTimeMillis()
    }
    fun running(ctx: Context): JSONObject? = state(ctx).optJSONObject("running")

    fun hav(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371.0; val dl = Math.toRadians(la2 - la1); val dn = Math.toRadians(lo2 - lo1)
        val x = sin(dl / 2).pow(2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dn / 2).pow(2)
        return 2 * r * asin(sqrt(x))
    }

    @Synchronized fun start(ctx: Context): String {
        val s = state(ctx); val now = System.currentTimeMillis()
        val r = s.optJSONObject("running")
        if (r != null) {
            if (r.has("ending")) return "waiting"
            if (!r.optBoolean("paused")) return "already"
            r.put("paused", false); r.put("legStart", now); r.put("legKm0", r.optDouble("gpsKm", 0.0))
            val stops = r.getJSONArray("stops"); if (stops.length() > 0) stops.getJSONObject(stops.length() - 1).put("resumed", now)
            persist(ctx); TrackService.start(ctx); return "resumed"
        }
        val n = JSONObject().put("id", "n" + now.toString(36)).put("start", now).put("legs", JSONArray()).put("stops", JSONArray())
            .put("track", JSONArray()).put("legStart", now).put("legKm0", 0.0).put("paused", false).put("gpsKm", 0.0)
        s.put("running", n); lastRec = null; lastRaw = null; lastKmPos = null
        persist(ctx); TrackService.start(ctx); return "started"
    }

    private fun closeLeg(r: JSONObject, now: Long) {
        if (r.optBoolean("paused")) return
        val km = round((r.optDouble("gpsKm", 0.0) - r.optDouble("legKm0", 0.0)) * 10) / 10
        r.getJSONArray("legs").put(JSONObject().put("start", r.getLong("legStart")).put("end", now).put("km", km))
    }

    @Synchronized fun pause(ctx: Context, fix: JSONObject?): String {
        val r = running(ctx) ?: return "none"; val now = System.currentTimeMillis()
        if (!r.optBoolean("paused")) {
            closeLeg(r, now); r.put("paused", true)
            r.getJSONArray("stops").put(JSONObject().put("at", now).put("lat", fix?.optDouble("lat") ?: "").put("lon", fix?.optDouble("lon") ?: "").put("resumed", ""))
        }
        persist(ctx); TrackService.stop(ctx); return "paused"
    }

    @Synchronized fun close(ctx: Context): String {
        val s = state(ctx); val r = s.optJSONObject("running") ?: return "none"; val now = System.currentTimeMillis()
        val end: Long
        if (!r.optBoolean("paused")) { closeLeg(r, now); end = now } else {
            val stops = r.getJSONArray("stops")
            end = if (stops.length() > 0) { val last = stops.getJSONObject(stops.length() - 1); stops.remove(stops.length() - 1); last.getLong("at") } else now
        }
        val legs = r.getJSONArray("legs"); var drive = 0L
        for (i in 0 until legs.length()) { val l = legs.getJSONObject(i); drive += l.getLong("end") - l.getLong("start") }
        val km = round(r.optDouble("gpsKm", 0.0) * 10) / 10
        val t = JSONObject().put("id", r.getString("id")).put("start", r.getLong("start")).put("end", end).put("driveMs", drive)
            .put("legs", legs).put("stops", r.getJSONArray("stops")).put("track", r.getJSONArray("track")).put("gpsKm", km).put("use", "")
        s.getJSONArray("finished").put(t); s.remove("running")
        persist(ctx); TrackService.stop(ctx); notifyEnded(ctx, t); return "closed"
    }

    fun homeDist(ctx: Context): Double? {
        val s = state(ctx); val h = s.optJSONObject("home") ?: return null; val f = s.optJSONObject("lastFix") ?: return null
        if (System.currentTimeMillis() - f.optLong("t") > 180_000) return null
        return hav(f.getDouble("lat"), f.getDouble("lon"), h.getDouble("lat"), h.getDouble("lon")) * 1000
    }

    /** Auto end (Bluetooth off / web "end"): close at home, otherwise record a stop. */
    @Synchronized fun endAuto(ctx: Context, interactive: Boolean): String {
        val s = state(ctx); val r = s.optJSONObject("running") ?: return "none"
        val d = homeDist(ctx); val radius = s.optJSONObject("home")?.optDouble("r", 300.0) ?: 300.0
        val fix = s.optJSONObject("lastFix")?.takeIf { System.currentTimeMillis() - it.optLong("t") < 180_000 }
        if (d != null) return if (d <= radius) close(ctx) else pause(ctx, fix)
        if (interactive) return "unknown"
        val res = pause(ctx, fix); notifyAskHome(ctx); return res
    }

    @Synchronized fun addFix(ctx: Context, loc: Location) {
        val s = state(ctx); val now = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        s.put("lastFix", JSONObject().put("t", System.currentTimeMillis()).put("lat", loc.latitude).put("lon", loc.longitude).put("acc", loc.accuracy.toDouble()))
        val r = s.optJSONObject("running") ?: return
        if (r.optBoolean("paused") || r.has("ending")) return
        var kmh: Double? = if (loc.hasSpeed()) loc.speed * 3.6 else null
        lastRaw?.let { if (kmh == null) { val dt = (now - it[0]) / 1000.0; if (dt > 0) kmh = hav(it[1], it[2], loc.latitude, loc.longitude) * 1000 / dt * 3.6 } }
        lastRaw = doubleArrayOf(now.toDouble(), loc.latitude, loc.longitude)
        r.put("curKmh", kmh ?: JSONObject.NULL)
        if (loc.accuracy > 60) { r.put("gpsStatus", "weak GPS"); return }
        val kp = lastKmPos
        if (kp == null) lastKmPos = doubleArrayOf(0.0, loc.latitude, loc.longitude) else {
            val d = hav(kp[1], kp[2], loc.latitude, loc.longitude)
            if (d * 1000 >= max(10.0, loc.accuracy.toDouble())) { r.put("gpsKm", r.optDouble("gpsKm", 0.0) + d); lastKmPos = doubleArrayOf(0.0, loc.latitude, loc.longitude) }
        }
        val lr = lastRec
        if (lr == null || now - lr[0] >= 5000 || hav(lr[1], lr[2], loc.latitude, loc.longitude) * 1000 >= 25) {
            val tr = r.getJSONArray("track")
            tr.put(JSONArray().put(now).put(round(loc.latitude * 1e6) / 1e6).put(round(loc.longitude * 1e6) / 1e6)
                .put(kmh?.let { round(it * 10) / 10 } ?: JSONObject.NULL).put(loc.accuracy.roundToInt()))
            lastRec = doubleArrayOf(now.toDouble(), loc.latitude, loc.longitude)
            if (tr.length() > 4000) { val n = JSONArray(); for (i in 0 until tr.length()) if (i % 2 == 0 || i == tr.length() - 1) n.put(tr.get(i)); r.put("track", n) }
        }
        r.put("gpsStatus", "ok")
        if (System.currentTimeMillis() - lastPersist > 15_000) persist(ctx)
    }

    @Synchronized fun setUse(ctx: Context, id: String, use: String) {
        val s = state(ctx); s.getJSONObject("uses").put(id, use)
        val f = s.getJSONArray("finished"); for (i in 0 until f.length()) if (f.getJSONObject(i).optString("id") == id) f.getJSONObject(i).put("use", use)
        persist(ctx)
    }

    @Synchronized fun syncJson(ctx: Context): String {
        val s = state(ctx)
        return JSONObject().put("running", s.opt("running") ?: JSONObject.NULL).put("finished", s.getJSONArray("finished"))
            .put("uses", s.getJSONObject("uses")).put("car", s.optString("car", "")).toString()
    }

    @Synchronized fun ack(ctx: Context, json: String) {
        val s = state(ctx); val a = JSONObject(json)
        val ids = a.optJSONArray("trips") ?: JSONArray(); val set = HashSet<String>(); for (i in 0 until ids.length()) set.add(ids.getString(i))
        val f = s.getJSONArray("finished"); val keep = JSONArray(); for (i in 0 until f.length()) if (!set.contains(f.getJSONObject(i).optString("id"))) keep.put(f.get(i))
        s.put("finished", keep)
        val us = a.optJSONArray("uses") ?: JSONArray(); val u = s.getJSONObject("uses"); for (i in 0 until us.length()) u.remove(us.getString(i))
        persist(ctx)
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "Trip tracking", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_PROMPT, "Trip prompts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openIntent(ctx: Context, extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra("open", extra)
        return PendingIntent.getActivity(ctx, (extra ?: "main").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, act: String, id: String, use: String, req: Int): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("id", id).putExtra("use", use)
        val pi = PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, use.ifEmpty { "End trip (I'm home)" }, pi).build()
    }

    private fun notifyEnded(ctx: Context, t: JSONObject) {
        channels(ctx); val id = t.getString("id"); val nid = id.hashCode()
        val n = Notification.Builder(ctx, CH_PROMPT).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip ended: %.1f km".format(t.getDouble("gpsKm"))).setContentText("Personal or Company?")
            .setContentIntent(openIntent(ctx, "trip:$id")).setAutoCancel(true)
            .addAction(action(ctx, ActionReceiver.SET_USE, id, "Personal", nid + 1)).addAction(action(ctx, ActionReceiver.SET_USE, id, "Company", nid + 2)).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(nid, n) } catch (e: SecurityException) {}
    }

    private fun notifyAskHome(ctx: Context) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_PROMPT).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip paused at a stop").setContentText("No GPS fix or home not set. Are you home?")
            .setContentIntent(openIntent(ctx, "trips")).setAutoCancel(true)
            .addAction(action(ctx, ActionReceiver.END_HOME, "", "", 99)).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(2, n) } catch (e: SecurityException) {}
    }

    fun notifyCantStart(ctx: Context) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_PROMPT).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Car connected — tap to start GPS tracking").setContentText("Android blocked background start. Allow battery 'Unrestricted' for Vehicle Log.")
            .setContentIntent(openIntent(ctx, "resume")).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(3, n) } catch (e: SecurityException) {}
    }
}
