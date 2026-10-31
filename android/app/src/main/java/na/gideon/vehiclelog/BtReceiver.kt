package na.gideon.vehiclelog

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/** Car radio Bluetooth connect = start/resume trip; disconnect = stop, or end at home.
 *  If no car is picked, any audio / hands-free / car-kit device acts as the trigger. */
class BtReceiver : BroadcastReceiver() {
    companion object {
        fun isCarLike(dev: BluetoothDevice): Boolean = try {
            val c = dev.bluetoothClass
            c == null || c.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                c.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (e: Exception) { true }
        fun name(dev: BluetoothDevice): String = try { dev.name ?: "?" } catch (e: SecurityException) { "?(no BT permission)" }
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
        if (!connected && intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        val pending = goAsync()
        try {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (dev == null) { TripStore.log(ctx, "BT event with no device"); return }
            val addr = try { dev.address ?: "" } catch (e: Exception) { "" }
            val nm = name(dev)
            val car = TripStore.state(ctx).optString("car", "")
            TripStore.log(ctx, "BT ${if (connected) "connected" else "disconnected"}: $nm [$addr]")
            val match = if (car.isNotEmpty()) addr.equals(car, ignoreCase = true) else isCarLike(dev)
            if (!match) { TripStore.log(ctx, if (car.isNotEmpty()) "  ignored (not your chosen car $car)" else "  ignored (not an audio/car device)"); return }
            if (connected) {
                TripStore.log(ctx, "  -> start: " + TripStore.start(ctx))
                if (car.isEmpty()) {
                    TripStore.state(ctx).put("lastAutoDev", JSONObject().put("name", nm).put("addr", addr)); TripStore.persist(ctx)
                    TripStore.notifySimple(ctx, 4, "Trip started from \"$nm\"", "No car chosen yet, so any Bluetooth audio/car device starts a trip. Pick your car in Vehicle Log > More.", "more")
                }
            } else TripStore.log(ctx, "  -> end: " + TripStore.endAuto(ctx, interactive = false))
        } catch (e: Exception) {
            TripStore.log(ctx, "BT receiver error: ${e.javaClass.simpleName}: ${e.message}")
        } finally { pending.finish() }
    }
}
