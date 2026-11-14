
package com.farshekashan.happguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class WatchService : Service() {

    companion object {
        const val ACTION_CLAIM =
            "com.farshekashan.happguard.CLAIM"

        const val ACTION_RELEASE =
            "com.farshekashan.happguard.RELEASE"

        private const val TAG = "HAPP_GUARD"
        private const val CHANNEL_ID = "happ_guard_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var stopped = false
    private var disconnectInProgress = false
    private var lastClaimQueuedAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (stopped) return

            enqueueStatusCheck()
            handler.postDelayed(this, POLL_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        handler.post(pollRunnable)
        Log.i(TAG, "WatchService started")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_CLAIM -> {
                val now = System.currentTimeMillis()

                if (now - lastClaimQueuedAt > 3000L) {
                    lastClaimQueuedAt = now
                    enqueueAction("CLAIM")
                }
            }

            ACTION_RELEASE -> {
                enqueueAction("RELEASE")
            }
        }

        return START_STICKY
    }

    private fun enqueueStatusCheck() {

        executor.execute {
            try {
                val response = postRequest("STATUS")

                Log.d(TAG, "STATUS: $response")

                if (response.optBoolean("disconnect", false)) {
                    requestDisconnectOnMainThread()
                }

            } catch (e: Exception) {
                Log.e(TAG, "STATUS request failed", e)
            }
        }
    }

    private fun enqueueAction(action: String) {

        executor.execute {
            try {
                val response = postRequest(action)
                Log.i(TAG, "$action response: $response")

            } catch (e: Exception) {
                Log.e(TAG, "$action failed", e)
            }
        }
    }

    private fun requestDisconnectOnMainThread() {

        if (disconnectInProgress) return

        disconnectInProgress = true

        handler.post {
            HappAccessibility.requestDisconnect { success ->

                executor.execute {

                    try {
                        val body = JSONObject().apply {
                            put(
                                "result",
                                if (success) "disconnected" else "failed"
                            )
                        }

                        if (success) {
                            val response = postRequest(
                                "ACK",
                                body
                            )

                            Log.i(TAG, "ACK: $response")

                        } else {
                            val response = postRequest(
                                "ACK",
                                body
                            )

                            Log.w(
                                TAG,
                                "Disconnect not confirmed: $response"
                            )
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Disconnect report failed", e)

                    } finally {
                        disconnectInProgress = false
                    }
                }
            }
        }
    }

    private fun postRequest(
        action: String,
        extra: JSONObject? = null
    ): JSONObject {

        val prefs = getSharedPreferences(
            "happ_guard",
            MODE_PRIVATE
        )

        val endpoint = prefs.getString(
            "endpoint",
            "https://farshkashanco.com/happguard/guard.php"
        ) ?: ""

        val secret = prefs.getString("secret", "") ?: ""

        if (endpoint.isBlank()) {
            throw IllegalStateException("Server address is empty")
        }

        if (secret.isBlank()) {
            throw IllegalStateException("Shared key is empty")
        }

        val deviceId = getGuardDeviceId()

        val payload = JSONObject().apply {
            put("key", secret)
            put("device", deviceId)
            put("action", action)

            if (extra != null) {
                val keys = extra.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, extra.get(key))
                }
            }
        }

        val connection = URL(endpoint)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )

            connection.setRequestProperty(
                "Cache-Control",
                "no-cache"
            )

            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream.bufferedReader().use {
                it.readText()
            }

            if (status !in 200..299) {
                throw IllegalStateException(
                    "HTTP $status: $responseText"
                )
            }

            val response = JSONObject(responseText)

            if (!response.optBoolean("ok", false)) {
                throw IllegalStateException(
                    response.optString("error", "server_error")
                )
            }

            return response

        } finally {
            connection.disconnect()
        }
    }

    private fun getGuardDeviceId(): String {

        val prefs = getSharedPreferences(
            "happ_guard",
            MODE_PRIVATE
        )

        var id = prefs.getString("device_id", null)

        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()

            prefs.edit()
                .putString("device_id", id)
                .apply()
        }

        return id
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "HAPP GUARD",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "HAPP GUARD background monitoring"

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HAPP GUARD فعال است")
            .setContentText("نظارت بر اتصال دستگاه‌ها فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacks(pollRunnable)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
