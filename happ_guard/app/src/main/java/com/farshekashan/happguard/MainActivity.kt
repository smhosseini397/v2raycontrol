
package com.farshekashan.happguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var endpointInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var statusText: TextView

    private val prefs by lazy {
        getSharedPreferences("happ_guard", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()
        createScreen()
        loadSettings()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()

        if (::statusText.isInitialized) {
            refreshStatus()
        }
    }

    private fun createScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(30, 35, 30, 30)
        }

        val title = TextView(this).apply {
            text = "HAPP GUARD"
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        statusText = TextView(this).apply {
            text = "وضعیت: آماده"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 20)
        }

        endpointInput = EditText(this).apply {
            hint = "آدرس سرور"
            textSize = 14f
            setSingleLine(true)
        }

        secretInput = EditText(this).apply {
            hint = "رمز مشترک دو گوشی"
            textSize = 14f
            setSingleLine(true)
        }

        val saveButton = Button(this).apply {
            text = "ذخیره تنظیمات"
            setOnClickListener {
                saveSettings()
            }
        }

        val startButton = Button(this).apply {
            text = "فعال کردن سرویس"
            setOnClickListener {
                if (saveSettings(false)) {
                    startGuardService()
                }
            }
        }

        val claimButton = Button(this).apply {
            text = "درخواست مالکیت"
            setOnClickListener {
                if (saveSettings(false)) {
                    startGuardService(WatchService.ACTION_CLAIM)
                }
            }
        }

        val releaseButton = Button(this).apply {
            text = "آزاد کردن مالکیت"
            setOnClickListener {
                if (saveSettings(false)) {
                    startGuardService(WatchService.ACTION_RELEASE)
                }
            }
        }

        val accessibilityButton = Button(this).apply {
            text = "فعال‌سازی دسترسی Accessibility"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                } catch (e: Exception) {
                    showMessage("خطا در بازکردن تنظیمات دسترسی: ${e.message}")
                }
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(endpointInput)
        layout.addView(secretInput)
        layout.addView(saveButton)
        layout.addView(startButton)
        layout.addView(claimButton)
        layout.addView(releaseButton)
        layout.addView(accessibilityButton)

        setContentView(layout)
    }

    private fun loadSettings() {

        endpointInput.setText(
            prefs.getString(
                "endpoint",
                "https://farshkashanco.com/happguard/guard.php"
            )
        )

        secretInput.setText(
            prefs.getString("secret", "")
        )
    }

    private fun saveSettings(
        showSuccessMessage: Boolean = true
    ): Boolean {

        val endpoint = endpointInput.text
            .toString()
            .trim()

        val secret = secretInput.text
            .toString()
            .trim()

        if (!endpoint.startsWith("https://")) {
            showMessage("آدرس سرور باید با https شروع شود")
            return false
        }

        if (secret.length < 16) {
            showMessage("رمز مشترک باید حداقل ۱۶ نویسه باشد")
            return false
        }

        prefs.edit()
            .putString("endpoint", endpoint)
            .putString("secret", secret)
            .apply()

        if (showSuccessMessage) {
            showMessage("تنظیمات ذخیره شد")
            statusText.text = "وضعیت: تنظیمات ذخیره شدند"
        }

        return true
    }

    private fun startGuardService(action: String? = null) {

        val intent = Intent(
            this,
            WatchService::class.java
        )

        if (action != null) {
            intent.action = action
        }

        try {
            ContextCompat.startForegroundService(
                this,
                intent
            )

            val message = when (action) {
                WatchService.ACTION_CLAIM ->
                    "درخواست مالکیت به سرویس ارسال شد"

                WatchService.ACTION_RELEASE ->
                    "درخواست آزادسازی به سرویس ارسال شد"

                else ->
                    "درخواست فعال‌سازی سرویس ارسال شد"
            }

            statusText.text = "وضعیت: $message"
            showMessage(message)

        } catch (e: Exception) {

            val error = "خطا در اجرای سرویس: ${e.message}"

            statusText.text = error
            showMessage(error)
        }
    }

    private fun refreshStatus() {

        val savedStatus = prefs.getString(
            "service_status",
            null
        )

        if (!savedStatus.isNullOrBlank()) {
            statusText.text = "وضعیت: $savedStatus"
        }
    }

    private fun requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33) {

            if (checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }

    private fun showMessage(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }
}
