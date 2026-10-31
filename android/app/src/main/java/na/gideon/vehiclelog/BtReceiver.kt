package na.gideon.vehiclelog

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Car radio Bluetooth connect = start/resume trip; disconnect = stop, or end at home. */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val car = TripStore.state(ctx).optString("car", "")
        if (car.isEmpty() || dev == null || !dev.address.equals(car, ignoreCase = true)) return
        val pending = goAsync()
        try {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> TripStore.start(ctx)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> TripStore.endAuto(ctx, interactive = false)
            }
        } finally { pending.finish() }
    }
}
