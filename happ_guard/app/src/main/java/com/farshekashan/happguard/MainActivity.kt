
package com.farshekashan.happguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {

    private lateinit var endpoint: EditText
    private lateinit var secret: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
        }

        root.addView(TextView(this).apply {
            text = "Happ Guard — نسخه آزمایشی\nروی هر دو گوشی نصب شود"
            textSize = 22f
        })

        endpoint = EditText(this).apply {
            hint = "آدرس کامل فایل guard.php"
            setText(p.getString("endpoint", "https://farshkashanco.com/happguard/guard.php"))
            setSingleLine(true)
        }

        secret = EditText(this).apply {
            hint = "کلید مشترک دو گوشی"
            setText(p.getString("secret", ""))
            setSingleLine(true)
        }

        root.addView(endpoint)
        root.addView(secret)

        val save = Button(this).apply {
            text = "ذخیره تنظیمات"
            setOnClickListener {
                p.edit()
                    .putString("endpoint", endpoint.text.toString().trim())
                    .putString("secret", secret.text.toString().trim())
                    .apply()

                status.text = "تنظیمات ذخیره شد"
            }
        }

        root.addView(save)

        root.addView(Button(this).apply {
            text = "فعال‌سازی مجوز قطع HAPP"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "شروع پایش این گوشی"
            setOnClickListener {
                p.edit()
                    .putString("endpoint", endpoint.text.toString().trim())
                    .putString("secret", secret.text.toString().trim())
                    .apply()

                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        5
                    )
                }

                startForegroundService(
                    Intent(this@MainActivity, WatchService::class.java)
                )

                status.text = "پایش فعال شد؛ برای درخواست مالکیت از دکمه بعدی استفاده کن."
            }
        })

        root.addView(Button(this).apply {
            text = "این گوشی مالک اتصال شود"
            setOnClickListener {
                startForegroundService(
                    Intent(this@MainActivity, WatchService::class.java)
                        .setAction("CLAIM")
                )

                status.text = "درخواست مالکیت ارسال شد. گوشی قبلی باید آنلاین و پایش آن فعال باشد."
            }
        })

        root.addView(Button(this).apply {
            text = "آزاد کردن مالکیت"
            setOnClickListener {
                startForegroundService(
                    Intent(this@MainActivity, WatchService::class.java)
                        .setAction("RELEASE")
                )

                status.text = "درخواست آزادسازی ارسال شد"
            }
        })

        status = TextView(this).apply {
            text = "وضعیت: آماده"
            textSize = 16f
        }

        root.addView(status)

        root.addView(TextView(this).apply {
            text = "مهم: این نسخه آزمایشی است. قطع خودکار فقط وقتی قابل اتکاست که سرویس دسترس‌پذیری فعال باشد و دکمه Disconnect در HAPP برای آن قابل شناسایی باشد. ابتدا روی دو گوشی آزمایش شود."
        })

        setContentView(ScrollView(this).apply {
            addView(root)
        })
    }
}
