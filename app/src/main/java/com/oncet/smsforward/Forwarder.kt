package com.oncet.smsforward

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 从短信正文里找验证码。 */
object CodeExtractor {
    private val KEYWORDS = Regex(
        "验证码|校验码|动态码|确认码|激活码|安全码|动态密码|短信密码|驗證碼|認證碼|验证代码|" +
            "code|otp|passcode|verification|一次性密码",
        RegexOption.IGNORE_CASE,
    )
    private val CANDIDATE = Regex("(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9A-Za-z])")

    /** 有关键词时，取离关键词最近的 4–8 位数字；没有关键词返回 null。 */
    fun find(text: String): String? {
        val keywords = KEYWORDS.findAll(text).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        return CANDIDATE.findAll(text)
            .minByOrNull { m -> keywords.minOf { k -> gap(m.range, k) } }
            ?.groupValues?.get(1)
    }

    private fun gap(a: IntRange, b: IntRange) = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}

object MessageBuilder {
    fun build(sender: String, text: String, sim: String?, time: Long): Outgoing {
        val code = CodeExtractor.find(text)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
        val subtitle = listOfNotNull(if (code != null) sender else null, sim, clock).joinToString(" · ")
        return Outgoing(
            title = code?.let { "验证码 $it" } ?: sender,
            subtitle = subtitle,
            body = text,
            code = code,
            sender = sender,
            time = time,
        )
    }
}

/** 推送到 iPhone 上的 Bark App（https://bark.day.app）。 */
object Forwarder {
    data class Result(val ok: Boolean, val retryable: Boolean, val detail: String)

    /** 支持直接粘贴 Bark 首页复制的链接，例如 https://api.day.app/AbCdEf123/推送内容 */
    fun parseBarkUrl(raw: String): Pair<String, String>? {
        val s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null
        val uri = Uri.parse(s)
        val host = uri.host ?: return null
        val key = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme}://$host$port" to key
    }

    fun send(cfg: Config, msg: Outgoing): Result {
        if (cfg.key.isBlank()) return Result(false, false, "未设置 Bark Key")
        val payload = JSONObject()
            .put("device_key", cfg.key)
            .put("title", msg.title)
            .put("subtitle", msg.subtitle)
            .put("body", msg.body)
            .put("group", "短信转发")
            .put("level", "timeSensitive")
            .put("isArchive", "1")
        if (msg.code != null) payload.put("copy", msg.code).put("autoCopy", "1")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${cfg.server}/push").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 6000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(text) }.getOrNull()
            val barkCode = json?.optInt("code", status) ?: status
            val barkMsg = json?.optString("message").orEmpty()
            when {
                status == 200 && barkCode == 200 -> Result(true, false, "ok")
                status in 400..499 || barkCode in 400..499 ->
                    Result(false, false, "Bark 拒绝：${barkMsg.ifEmpty { "HTTP $status" }}")
                else -> Result(false, true, "服务器错误 HTTP $status")
            }
        } catch (e: IOException) {
            Result(false, true, "网络不通")
        } catch (e: Exception) {
            Result(false, false, e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}

/** 收到短信后的完整处理：过滤 → 推送 → 失败排队。在后台线程调用。 */
object Dispatcher {
    fun handleIncoming(ctx: Context, sender: String, text: String, sim: String?, time: Long) {
        val cfg = Config(ctx)
        val msg = MessageBuilder.build(sender, text, sim, time)
        val skip = when {
            !cfg.enabled -> "⏸ 转发已关闭"
            cfg.key.isBlank() -> "⚠️ 未设置 Bark Key，没发出去"
            cfg.codesOnly && msg.code == null -> "⏭ 不是验证码，已跳过"
            else -> null
        }
        if (skip != null) {
            LogStore.add(ctx, sender, msg.summary, skip)
            return
        }

        var r = Forwarder.send(cfg, msg)
        if (!r.ok && r.retryable) {
            Thread.sleep(2000)
            r = Forwarder.send(cfg, msg)
        }
        when {
            r.ok -> LogStore.add(ctx, sender, msg.summary, "✅ 已转发")
            r.retryable -> {
                PendingStore.add(ctx, msg)
                RetryJobService.schedule(ctx)
                LogStore.add(ctx, sender, msg.summary, "⏳ ${r.detail}，联网后自动补发")
            }
            else -> LogStore.add(ctx, sender, msg.summary, "❌ ${r.detail}")
        }
    }
}
