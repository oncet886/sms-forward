package com.oncet.smsforward

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return

        val sim = simLabel(intent)
        // 长短信会拆成多段，按发件人拼回去
        val bySender = parts.filterNotNull().groupBy { it.originatingAddress ?: "未知号码" }
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                for ((sender, ps) in bySender) {
                    val text = ps.joinToString("") { it.messageBody ?: "" }
                    Dispatcher.handleIncoming(app, sender, text, sim, ps.first().timestampMillis)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** 双卡手机标出是哪张卡收到的；各厂商字段名不同，取得到就显示。 */
    @Suppress("DEPRECATION")
    private fun simLabel(intent: Intent): String? {
        val extras = intent.extras ?: return null
        val keys = listOf("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "slot_id", "slotId", "simId")
        val slot = keys.firstNotNullOfOrNull { extras.get(it)?.toString()?.toIntOrNull() } ?: return null
        return if (slot in 0..3) "卡${slot + 1}" else null
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeepAliveService.sync(context)
        if (PendingStore.count(context) > 0) RetryJobService.schedule(context)
    }
}

/** 联网后补发 PendingStore 里的消息。 */
class RetryJobService : JobService() {
    @Volatile
    private var stopped = false

    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        thread {
            val cfg = Config(this)
            var needRetry = false
            for (item in PendingStore.all(this)) {
                if (stopped) break
                if (System.currentTimeMillis() - item.msg.time > MAX_AGE_MS) {
                    PendingStore.remove(this, item.id)
                    LogStore.add(this, item.msg.sender, item.msg.summary, "🗑 超过 3 天仍未发出，已放弃")
                    continue
                }
                val r = Forwarder.send(cfg, item.msg)
                when {
                    r.ok -> {
                        PendingStore.remove(this, item.id)
                        LogStore.add(this, item.msg.sender, item.msg.summary, "✅ 补发成功")
                    }
                    r.retryable -> {
                        needRetry = true
                        break // 网络还是不通，别一条条硬试
                    }
                    else -> {
                        PendingStore.remove(this, item.id)
                        LogStore.add(this, item.msg.sender, item.msg.summary, "❌ ${r.detail}")
                    }
                }
            }
            if (!stopped) jobFinished(params, needRetry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped = true
        return true
    }

    companion object {
        private const val JOB_ID = 1001
        private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, RetryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            js.schedule(job)
        }
    }
}
