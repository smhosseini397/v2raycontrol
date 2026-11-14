package com.farshekashan.happguard

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WatchService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val deviceId by lazy { getSharedPreferences("cfg", MODE_PRIVATE).let { p -> p.getString("device", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device", it).apply() } } }
    override fun onCreate() {
        super.onCreate()
        val channel = "happ_guard"
        if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "Happ Guard", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, Notification.Builder(this, channel).setContentTitle("Happ Guard فعال است").setContentText("در حال بررسی مالکیت اتصال").setSmallIcon(android.R.drawable.ic_lock_lock).build())
        executor.scheduleWithFixedDelay({ poll("STATUS") }, 0, 3, TimeUnit.SECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { "CLAIM" -> executor.execute { poll("CLAIM") }; "RELEASE" -> executor.execute { poll("RELEASE") } }
        return START_STICKY
    }
    private fun poll(action: String) {
        try {
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            val endpoint = p.getString("endpoint", "") ?: ""
            val secret = p.getString("secret", "") ?: ""
            if (endpoint.isBlank() || secret.isBlank()) return
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000; doOutput = true; setRequestProperty("Content-Type", "application/json") }
            conn.outputStream.use { it.write(JSONObject().put("key", secret).put("device", deviceId).put("action", action).toString().toByteArray()) }
            val body = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect()
            val result = JSONObject(body)
            if (result.optBoolean("disconnect", false)) { HappAccessibility.requestDisconnect(); executor.schedule({ poll("ACK") }, 2, TimeUnit.SECONDS) }
        } catch (e: Exception) { Log.w("HappGuard", "poll failed: ${e.message}") }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
