
package com.farshekashan.happguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HappAccessibility : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        if (pkg != HAPP_PACKAGE) return

        val now = SystemClock.elapsedRealtime()

        if (now - lastClaimTime < 2000) return

        lastClaimTime = now

        try {
            val intent = Intent(this, WatchService::class.java)
                .setAction("CLAIM")

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun disconnectHapp(): Boolean {
        // First try Happ's direct disconnect command.
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse("happ://disconnect_without_ui")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(HAPP_PACKAGE)
            }

            startActivity(intent)
            return true

        } catch (_: Exception) {
        }

        // Fallback: open Happ and click its disconnect control.
        return try {
            val launch = packageManager.getLaunchIntentForPackage(HAPP_PACKAGE)
                ?: return false

            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)

            handler.postDelayed({
                clickDisconnect(rootInActiveWindow)
            }, 1200)

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clickDisconnect(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val words = listOf(
            "disconnect", "stop", "قطع اتصال",
            "قطع", "توقف", "خاموش"
        )

        val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo) {
            val label = (
                node.text?.toString().orEmpty() + " " +
                node.contentDescription?.toString().orEmpty()
            ).lowercase()

            if (words.any { label.contains(it.lowercase()) }) {
                nodes.add(node)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(root)

        for (node in nodes) {
            var target: AccessibilityNodeInfo? = node

            while (target != null) {
                if (target.isClickable) {
                    if (target.performAction(
                            AccessibilityNodeInfo.ACTION_CLICK
                        )
                    ) return true
                }

                target = target.parent
            }
        }

        return false
    }

    companion object {
        private const val HAPP_PACKAGE = "com.happproxy"

        @Volatile
        private var instance: HappAccessibility? = null

        @Volatile
        private var lastClaimTime = 0L

        fun requestDisconnect(): Boolean {
            val service = instance ?: return false
            return service.disconnectHapp()
        }
    }
}
