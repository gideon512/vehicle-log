package na.gideon.vehiclelog

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.app.NotificationManager

/** Foreground location service: records GPS while a trip leg is running, screen on or off. */
class TrackService : Service(), LocationListener {
    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, TrackService::class.java)) }
            catch (e: Exception) { TripStore.notifyCantStart(ctx) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TrackService::class.java)) }
    }
    private var lm: LocationManager? = null
    private var lastNotif = 0L

    private fun notif(text: String): Notification = Notification.Builder(this, TripStore.CH_TRACK)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Trip recording").setContentText(text)
        .setOngoing(true).setContentIntent(TripStore.openIntent(this, "trips")).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TripStore.channels(this)
        val n = notif("Waiting for GPS…")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(TripStore.NOTIF_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(TripStore.NOTIF_TRACK, n)
        } catch (e: Exception) { TripStore.notifyCantStart(this); stopSelf(); return START_NOT_STICKY }
        if (lm == null) {
            lm = getSystemService(LocationManager::class.java)
            try {
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) { getSystemService(NotificationManager::class.java).notify(TripStore.NOTIF_TRACK, notif("Location permission missing — open the app")) }
        }
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        TripStore.addFix(this, loc)
        val now = System.currentTimeMillis()
        if (now - lastNotif > 10_000) {
            lastNotif = now
            val r = TripStore.running(this)
            val km = r?.optDouble("gpsKm", 0.0) ?: 0.0
            try { getSystemService(NotificationManager::class.java).notify(TripStore.NOTIF_TRACK, notif("%.1f km · %d km/h".format(km, (if (loc.hasSpeed()) loc.speed * 3.6 else 0.0).toInt()))) } catch (e: SecurityException) {}
        }
    }
    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onDestroy() { try { lm?.removeUpdates(this) } catch (e: Exception) {}; TripStore.persist(this); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
