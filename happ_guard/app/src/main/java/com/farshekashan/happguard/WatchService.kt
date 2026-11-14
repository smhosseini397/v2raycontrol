
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
import java.util.concurrent.atomic.AtomicBoolean

class WatchService : Service() {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val disconnectInProgress = AtomicBoolean(false)

    private val deviceId by lazy {
        getSharedPreferences("cfg", MODE_PRIVATE).let { p ->
            p.getString("device", null)
                ?: UUID.randomUUID().toString().also {
                    p.edit().putString("device", it).apply()
                }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val channel = "happ_guard"

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        channel,
                        "Happ Guard",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }

        val notification = Notification.Builder(this, channel)
            .setContentTitle("Happ Guard فعال است")
            .setContentText("پایش خودکار اتصال فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()

        startForeground(7, notification)

        executor.scheduleWithFixedDelay(
            { poll("STATUS") },
            0,
            500,
            TimeUnit.MILLISECONDS
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            "CLAIM" -> executor.execute { poll("CLAIM") }
            "RELEASE" -> executor.execute { poll("RELEASE") }
        }

        return START_STICKY
    }

    private fun poll(action: String) {
        var conn: HttpURLConnection? = null

        try {
            val p = getSharedPreferences("cfg", MODE_PRIVATE)
            val endpoint = p.getString("endpoint", "") ?: ""
            val secret = p.getString("secret", "") ?: ""

            if (endpoint.isBlank() || secret.isBlank()) return

            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 2500
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
                setRequestProperty("Cache-Control", "no-cache")
            }

            val payload = JSONObject()
                .put("key", secret)
                .put("device", deviceId)
                .put("action", action)

            conn.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val body = conn.inputStream.bufferedReader().use {
                it.readText()
            }

            val result = JSONObject(body)

            if (result.optBoolean("disconnect", false)) {
                if (disconnectInProgress.compareAndSet(false, true)) {

                    val accepted =
                        HappAccessibility.requestDisconnect()

                    if (accepted) {
                        executor.schedule({
                            try {
                                poll("ACK")
                            } finally {
                                disconnectInProgress.set(false)
                            }
                        }, 1200, TimeUnit.MILLISECONDS)

                    } else {
                        disconnectInProgress.set(false)
                        Log.w(
                            "HappGuard",
                            "Accessibility service unavailable"
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.w("HappGuard", "poll failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
