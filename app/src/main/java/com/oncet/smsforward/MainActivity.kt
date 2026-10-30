package com.oncet.smsforward

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var tvStatus: TextView
    private lateinit var tvPerms: TextView
    private lateinit var tvLog: TextView
    private lateinit var etKey: EditText
    private lateinit var etServer: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cfg = Config(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPerms = findViewById(R.id.tvPerms)
        tvLog = findViewById(R.id.tvLog)
        etKey = findViewById(R.id.etKey)
        etServer = findViewById(R.id.etServer)

        etKey.setText(cfg.key)
        etServer.setText(cfg.server)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (save()) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.btnGrant).setOnClickListener { requestPerms() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestBattery() }
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener { openAppSettings() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { LogStore.clear(this) }

        bindSwitch(R.id.swEnabled, cfg.enabled) { cfg.enabled = it }
        bindSwitch(R.id.swCodesOnly, cfg.codesOnly) { cfg.codesOnly = it }
        bindSwitch(R.id.swKeepAlive, cfg.keepAlive) { cfg.keepAlive = it }
    }

    override fun onResume() {
        super.onResume()
        LogStore.listener = { renderLog() }
        KeepAliveService.sync(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        LogStore.listener = null
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                KeepAliveService.sync(this@MainActivity)
                refresh()
            }
        }
    }

    /** 把输入框写进设置；粘贴的是整条 Bark 链接时自动拆出服务器和 Key。 */
    private fun save(): Boolean {
        val keyInput = etKey.text.toString().trim()
        var server = etServer.text.toString().trim().ifEmpty { Config.DEFAULT_SERVER }
        var key = keyInput
        Forwarder.parseBarkUrl(keyInput)?.let { (s, k) ->
            server = s
            key = k
        }
        if (!server.startsWith("https://") && !server.startsWith("http://")) {
            Toast.makeText(this, "服务器地址要以 https:// 开头", Toast.LENGTH_LONG).show()
            return false
        }
        cfg.server = server
        cfg.key = key
        etKey.setText(cfg.key)
        etServer.setText(cfg.server)
        refresh()
        return true
    }

    private fun sendTest() {
        if (!save()) return
        if (cfg.key.isBlank()) {
            Toast.makeText(this, "先粘贴 Bark 链接", Toast.LENGTH_LONG).show()
            return
        }
        val msg = MessageBuilder.build(
            "测试", "这是一条测试消息，iPhone 收到就说明配置成功。您的验证码是 123456。",
            null, System.currentTimeMillis()
        )
        Toast.makeText(this, "正在发送…", Toast.LENGTH_SHORT).show()
        thread {
            val r = Forwarder.send(cfg, msg)
            LogStore.add(this, "测试", msg.summary, if (r.ok) "✅ 测试发送成功" else "❌ ${r.detail}")
            runOnUiThread {
                val text = if (r.ok) "已发出，看看 iPhone 有没有收到" else "发送失败：${r.detail}"
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun neededPerms(): List<String> = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val missing = neededPerms().filterNot(::granted)
        if (missing.isEmpty()) {
            Toast.makeText(this, "权限都已授予", Toast.LENGTH_SHORT).show()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        KeepAliveService.sync(this)
        refresh()
        if (!granted(Manifest.permission.RECEIVE_SMS)) {
            Toast.makeText(this, "短信权限被拒绝了，请在系统设置 → 权限里手动打开", Toast.LENGTH_LONG).show()
            openAppSettings()
        }
    }

    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestBattery() {
        if (batteryOk()) {
            Toast.makeText(this, "已经是不限制了", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        val sms = granted(Manifest.permission.RECEIVE_SMS)
        val notify = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val battery = batteryOk()

        tvPerms.text = listOf(
            mark(sms) + " 接收短信",
            mark(notify) + " 通知（常驻通知需要）",
            mark(battery) + " 后台不受电池限制",
        ).joinToString("\n")

        val problems = buildList {
            if (!sms) add("还没授予短信权限")
            if (cfg.key.isBlank()) add("还没填 Bark 链接")
            if (!cfg.enabled) add("转发开关是关着的")
        }
        val pending = PendingStore.count(this)
        tvStatus.text = when {
            problems.isNotEmpty() -> "⚠️ 还不能用：" + problems.joinToString("、")
            !battery -> "🟡 能用，但建议点「允许后台运行」，否则可能被系统杀掉"
            else -> "✅ 已就绪：收到短信会自动推送到 iPhone"
        } + if (pending > 0) "\n有 $pending 条等联网后补发" else ""

        renderLog()
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "❌"

    private fun renderLog() {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val entries = LogStore.all(this)
        tvLog.text = if (entries.isEmpty()) {
            "还没有记录"
        } else {
            entries.joinToString("\n") { e ->
                "${fmt.format(Date(e.time))}  ${e.sender}\n    ${e.summary}  ${e.status}"
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 1
    }
}
