package na.gideon.vehiclelog

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionReceiver : BroadcastReceiver() {
    companion object { const val SET_USE = "na.gideon.vehiclelog.SET_USE"; const val END_HOME = "na.gideon.vehiclelog.END_HOME" }
    override fun onReceive(ctx: Context, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        when (intent.action) {
            SET_USE -> { val id = intent.getStringExtra("id") ?: return; TripStore.setUse(ctx, id, intent.getStringExtra("use") ?: ""); nm.cancel(id.hashCode()) }
            END_HOME -> { nm.cancel(2); TripStore.close(ctx) }
        }
    }
}
