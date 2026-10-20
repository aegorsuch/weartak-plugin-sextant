package com.weartak.plugin.sextant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.weartak.plugin.sextant.R
import org.json.JSONObject

/** Foreground recorder: periodic Wi-Fi scans + barometer + step counter + GPS, logged to JSONL. */
class SextantService : Service(), SensorEventListener, LocationListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var sensors: SensorManager
    private lateinit var locations: LocationManager
    private var lastBaroLog = 0L
    private var registered = false

    private val scanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { SextantEngine.onScanResults(wifi.scanResults) }
                .onFailure { Log.w(TAG, "scan results failed", it) }
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            requestScan()
            handler.postDelayed(this, if (SextantEngine.throttled) 30_000L else 6_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SextantEngine.init(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        locations = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SCAN -> { if (registered) requestScan(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!registered) startSensing()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startSensing() {
        registered = true
        ScanThrottle.disable(this)
        SextantEngine.throttled = ScanThrottle.isThrottled(this)
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        runCatching { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, this, Looper.getMainLooper()) }
            .onFailure { Log.w(TAG, "gps unavailable", it) }
        runCatching { SextantEngine.onScanResults(wifi.scanResults) }
        SextantEngine.recording = true
        SextantEngine.stepsAtRecordStart = null
        SextantEngine.log("record_start", JSONObject().put("throttled", SextantEngine.throttled))
        handler.post(scanLoop)
    }

    private fun requestScan() {
        @Suppress("DEPRECATION") val ok = runCatching { wifi.startScan() }.getOrDefault(false)
        if (!ok) Log.d(TAG, "startScan rejected (throttled?)")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val v = event.values[0]
                val prev = SextantEngine.pressure
                SextantEngine.pressure = if (prev == null) v else prev * 0.9f + v * 0.1f
                val now = System.currentTimeMillis()
                if (now - lastBaroLog > 5_000) {
                    lastBaroLog = now
                    SextantEngine.log("baro", JSONObject().put("hpa", SextantEngine.pressure))
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val s = event.values[0].toLong()
                if (SextantEngine.stepsAtRecordStart == null) SextantEngine.stepsAtRecordStart = s
                SextantEngine.stepCounter = s
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        SextantEngine.gps = location
        SextantEngine.log("gps", JSONObject().put("lat", location.latitude).put("lon", location.longitude)
            .put("acc", location.accuracy.toDouble()).put("alt", location.altitude))
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            runCatching { unregisterReceiver(scanReceiver) }
            sensors.unregisterListener(this)
            runCatching { locations.removeUpdates(this) }
            SextantEngine.log("record_stop", JSONObject())
        }
        ScanThrottle.restore(this)
        registered = false
        SextantEngine.recording = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sextant recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SextantActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.sextant_pin)
            .setContentTitle("Sextant recording")
            .setContentText("Wi-Fi + barometer + steps")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "SextantService"
        private const val CHANNEL = "sextant"
        private const val NOTIF_ID = 47_011
        const val ACTION_STOP = "sextant.STOP"
        const val ACTION_SCAN = "sextant.SCAN"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, SextantService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, SextantService::class.java).setAction(ACTION_STOP))
        fun scanNow(ctx: Context) {
            if (SextantEngine.recording) ctx.startService(Intent(ctx, SextantService::class.java).setAction(ACTION_SCAN))
        }
    }
}
