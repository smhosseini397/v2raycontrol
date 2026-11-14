
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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val statusCheckRunning = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    private var disconnectInProgress = false

    @Volatile
    private var currentStatus = ""

    private val pollRunnable = object : Runnable {

        override fun run() {

            if (stopped) return

            enqueueStatusCheck()

            handler.postDelayed(
                this,
                POLL_INTERVAL
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        startForeground(
            NOTIFICATION_ID,
            createNotification("در حال بررسی وضعیت اتصال")
        )

        updateStatus("سرویس فعال است؛ در حال بررسی سرور")

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

                updateStatus("درخواست مالکیت در حال ارسال است")

                enqueueAction("CLAIM")
            }

            ACTION_RELEASE -> {

                updateStatus("درخواست آزادسازی مالکیت در حال ارسال است")

                enqueueAction("RELEASE")
            }

            else -> {

                Log.i(TAG, "Service started without action")
            }
        }

        return START_STICKY
    }

    private fun enqueueStatusCheck() {

        if (stopped) return

        if (!statusCheckRunning.compareAndSet(false, true)) {
            return
        }

        try {

            executor.execute {

                try {

                    val response = postRequest("STATUS")

                    Log.d(TAG, "STATUS response: $response")

                    if (response.optBoolean("disconnect", false)) {

                        updateStatus(
                            "درخواست قطع اتصال از سرور دریافت شد"
                        )

                        requestDisconnectOnMainThread()

                    } else {

                        val message = response.optString(
                            "message",
                            ""
                        )

                        when (message) {

                            "you_are_owner" -> {
                                updateStatus(
                                    "این گوشی مالک اتصال است"
                                )
                            }

                            "waiting_for_ack" -> {
                                updateStatus(
                                    "در انتظار تأیید قطع اتصال گوشی قبلی"
                                )
                            }

                            "not_owner" -> {
                                updateStatus(
                                    "این گوشی مالک اتصال نیست"
                                )
                            }

                            "disconnect_requested" -> {
                                updateStatus(
                                    "درخواست قطع اتصال دریافت شد"
                                )
                            }

                            else -> {
                                if (message.isNotBlank()) {
                                    updateStatus(
                                        "وضعیت سرور: $message"
                                    )
                                }
                            }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(TAG, "STATUS request failed", e)

                    updateStatus(
                        "خطای ارتباط با سرور: ${e.message}"
                    )

                } finally {

                    statusCheckRunning.set(false)
                }
            }

        } catch (e: Exception) {

            statusCheckRunning.set(false)

            Log.e(TAG, "Cannot queue STATUS", e)

            updateStatus(
                "خطا در اجرای بررسی وضعیت: ${e.message}"
            )
        }
    }

    private fun enqueueAction(action: String) {

        if (stopped) return

        try {

            executor.execute {

                try {

                    val response = postRequest(action)

                    Log.i(
                        TAG,
                        "$action response: $response"
                    )

                    val message = response.optString(
                        "message",
                        ""
                    )

                    when (action) {

                        "CLAIM" -> {

                            if (response.optBoolean(
                                    "can_connect",
                                    false
                                )
                            ) {

                                updateStatus(
                                    "مالکیت تأیید شد؛ اجازه اتصال دارید"
                                )

                            } else {

                                updateStatus(
                                    when (message) {

                                        "waiting_for_previous_device" ->
                                            "در انتظار قطع اتصال گوشی قبلی"

                                        else ->
                                            "درخواست مالکیت ثبت شد: $message"
                                    }
                                )
                            }
                        }

                        "RELEASE" -> {

                            updateStatus(
                                "نتیجه آزادسازی مالکیت: $message"
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.e(TAG, "$action failed", e)

                    updateStatus(
                        "خطای $action: ${e.message}"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(TAG, "Cannot queue $action", e)

            updateStatus(
                "خطا در ثبت درخواست: ${e.message}"
            )
        }
    }

    private fun requestDisconnectOnMainThread() {

        handler.post {

            if (stopped || disconnectInProgress) {
                return@post
            }

            disconnectInProgress = true

            updateStatus(
                "در حال درخواست قطع اتصال از Happ"
            )

            Log.i(
                TAG,
                "Calling HappAccessibility.requestDisconnect"
            )

            HappAccessibility.requestDisconnect { success ->

                Log.i(
                    TAG,
                    "Accessibility callback: success=$success"
                )

                try {

                    executor.execute {

                        try {

                            val body = JSONObject().apply {

                                put(
                                    "result",
                                    if (success) {
                                        "disconnected"
                                    } else {
                                        "failed"
                                    }
                                )
                            }

                            val response = postRequest(
                                "ACK",
                                body
                            )

                            Log.i(TAG, "ACK response: $response")

                            if (success) {

                                updateStatus(
                                    "قطع اتصال تأیید شد؛ پاسخ سرور: " +
                                        response.optString("message")
                                )

                            } else {

                                updateStatus(
                                    "قطع اتصال تأیید نشد؛ پاسخ سرور: " +
                                        response.optString("message")
                                )
                            }

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Disconnect report failed",
                                e
                            )

                            updateStatus(
                                "خطا در گزارش قطع اتصال: ${e.message}"
                            )

                        } finally {

                            handler.post {
                                disconnectInProgress = false
                            }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Cannot queue ACK",
                        e
                    )

                    updateStatus(
                        "خطا در ثبت نتیجه قطع اتصال: ${e.message}"
                    )

                    disconnectInProgress = false
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

        val secret = prefs.getString(
            "secret",
            ""
        ) ?: ""

        if (endpoint.isBlank()) {
            throw IllegalStateException(
                "Server address is empty"
            )
        }

        if (secret.isBlank()) {
            throw IllegalStateException(
                "Shared key is empty"
            )
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

                    put(
                        key,
                        extra.get(key)
                    )
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
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Cache-Control",
                "no-cache"
            )

            connection.outputStream.use {

                it.write(
                    payload.toString().toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val status = connection.responseCode

            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IllegalStateException(
                "Empty server response: HTTP $status"
            )

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
                    response.optString(
                        "error",
                        "server_error"
                    )
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

        var id = prefs.getString(
            "device_id",
            null
        )

        if (id.isNullOrBlank()) {

            id = UUID.randomUUID().toString()

            prefs.edit()
                .putString("device_id", id)
                .apply()
        }

        return id
    }

    private fun updateStatus(message: String) {

        if (message == currentStatus) return

        currentStatus = message

        getSharedPreferences(
            "happ_guard",
            MODE_PRIVATE
        ).edit()
            .putString("service_status", message)
            .apply()

        Log.i(TAG, "STATUS UPDATE: $message")

        try {

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.notify(
                NOTIFICATION_ID,
                createNotification(message)
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Notification update failed",
                e
            )
        }
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

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        message: String
    ): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("HAPP GUARD")
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )
            .setSmallIcon(
                android.R.drawable.ic_lock_lock
            )
            .setOngoing(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    override fun onDestroy() {

        stopped = true

        handler.removeCallbacks(pollRunnable)

        executor.shutdownNow()

        Log.w(TAG, "WatchService destroyed")

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
