
package com.farshekashan.happguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class HappAccessibility : AccessibilityService() {

    companion object {
        private const val TAG = "HAPP_ACCESSIBILITY"
        private const val TARGET_PACKAGE = "com.happproxy"

        @Volatile
        private var serviceInstance: HappAccessibility? = null

        fun requestDisconnect(callback: (Boolean) -> Unit) {
            val service = serviceInstance

            if (service == null) {
                callback(false)
                return
            }

            service.handler.post {
                service.beginDisconnect(callback)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private var lastClaimTime = 0L
    private var disconnecting = false
    private var disconnectCallback: ((Boolean) -> Unit)? = null
    private var disconnectTimeout: Runnable? = null
    private var scanTask: Runnable? = null
    private var clickAttempted = false

    private val disconnectWords = listOf(
        "disconnect",
        "disconnect vpn",
        "stop connection",
        "stop",
        "قطع اتصال",
        "قطع ارتباط",
        "توقف اتصال",
        "خاموش کردن"
    )

    private val disconnectedWords = listOf(
        "disconnected",
        "not connected",
        "connection stopped",
        "قطع شده",
        "متصل نیست",
        "اتصال برقرار نیست"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInstance = this
        Log.i(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        if (packageName != TARGET_PACKAGE) return

        if (disconnecting) {
            scanCurrentWindow()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val now = System.currentTimeMillis()

            // جلوگیری از ارسال مکرر CLAIM در هر تغییر صفحه
            if (now - lastClaimTime >= 15000L) {
                lastClaimTime = now
                sendAutomaticClaim()
            }
        }
    }

    private fun sendAutomaticClaim() {
        try {
            val intent = Intent(
                this,
                WatchService::class.java
            ).apply {
                action = WatchService.ACTION_CLAIM
            }

            startForegroundService(intent)

            Log.i(TAG, "Automatic CLAIM requested")

        } catch (e: Exception) {
            Log.e(TAG, "Automatic CLAIM failed", e)
        }
    }

    private fun beginDisconnect(callback: (Boolean) -> Unit) {

        if (disconnecting) {
            callback(false)
            return
        }

        disconnecting = true
        clickAttempted = false
        disconnectCallback = callback

        // حداکثر زمان انتظار برای مشاهده وضعیت قطع
        disconnectTimeout = Runnable {
            finishDisconnect(false)
        }

        handler.postDelayed(disconnectTimeout!!, 15000L)

        scanTask = object : Runnable {
            override fun run() {
                if (!disconnecting) return

                scanCurrentWindow()
                handler.postDelayed(this, 400L)
            }
        }

        handler.post(scanTask!!)
    }

    private fun scanCurrentWindow() {

        val root = rootInActiveWindow ?: return

        if (root.packageName?.toString() != TARGET_PACKAGE) return

        if (isDisconnected(root)) {
            finishDisconnect(true)
            return
        }

        if (!clickAttempted) {
            clickDisconnectButton(root)
        }
    }

    private fun clickDisconnectButton(root: AccessibilityNodeInfo) {

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        for (node in nodes) {

            val text = node.text?.toString()
                ?.trim()
                ?.lowercase()
                ?: ""

            val description = node.contentDescription?.toString()
                ?.trim()
                ?.lowercase()
                ?: ""

            val combined = "$text $description"

            val matched = disconnectWords.any {
                combined.contains(it.lowercase())
            }

            if (!matched) continue

            var clickable: AccessibilityNodeInfo? = node

            while (clickable != null && !clickable.isClickable) {
                clickable = clickable.parent
            }

            if (clickable != null && clickable.isClickable) {

                val clicked = clickable.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )

                if (clicked) {
                    clickAttempted = true
                    Log.i(TAG, "Disconnect button clicked")
                    return
                }
            }
        }
    }

    private fun isDisconnected(root: AccessibilityNodeInfo): Boolean {

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        for (node in nodes) {

            val text = node.text?.toString()
                ?.trim()
                ?.lowercase()
                ?: ""

            val description = node.contentDescription?.toString()
                ?.trim()
                ?.lowercase()
                ?: ""

            val combined = "$text $description"

            if (disconnectedWords.any {
                    combined.contains(it.lowercase())
                }) {
                return true
            }
        }

        return false
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>
    ) {
        output.add(node)

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectNodes(child, output)
        }
    }

    private fun finishDisconnect(success: Boolean) {

        if (!disconnecting) return

        disconnecting = false

        disconnectTimeout?.let {
            handler.removeCallbacks(it)
        }

        scanTask?.let {
            handler.removeCallbacks(it)
        }

        disconnectTimeout = null
        scanTask = null

        val callback = disconnectCallback
        disconnectCallback = null

        Log.i(TAG, "Disconnect result: $success")

        callback?.invoke(success)
    }

    override fun onInterrupt() {
        finishDisconnect(false)
    }

    override fun onDestroy() {
        if (serviceInstance === this) {
            serviceInstance = null
        }

        finishDisconnect(false)
        super.onDestroy()
    }
}
