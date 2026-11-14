package com.farshekashan.happguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper

class HappAccessibility : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }
    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    fun disconnectHapp() {
        // Use an explicit DISCONNECT command, never a toggle: toggling could turn
        // the tunnel ON if it was already disconnected.
        try {
            val disconnect = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("happ://disconnect_without_ui")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.happproxy")
            }
            startActivity(disconnect)
        } catch (_: Exception) {
            // Fallback for HAPP builds that do not expose the no-UI URI.
            val launch = packageManager.getLaunchIntentForPackage("com.happproxy")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                Handler(Looper.getMainLooper()).postDelayed({ clickDisconnect(rootInActiveWindow) }, 1800)
            }
        }
    }
    private fun clickDisconnect(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val words = listOf("disconnect", "stop", "قطع", "توقف", "خاموش")
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            val label = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            if (words.any { label.contains(it, true) }) nodes.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        val target = nodes.firstOrNull { it.isClickable } ?: nodes.firstOrNull()?.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }
    companion object {
        @Volatile private var instance: HappAccessibility? = null
        fun requestDisconnect() { Handler(Looper.getMainLooper()).post { instance?.disconnectHapp() } }
    }
}
