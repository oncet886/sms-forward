package com.oncet.smsforward

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** 用户设置。 */
class Config(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var key: String
        get() = sp.getString("key", "") ?: ""
        set(v) = sp.edit().putString("key", v.trim()).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var codesOnly: Boolean
        get() = sp.getBoolean("codesOnly", false)
        set(v) = sp.edit().putBoolean("codesOnly", v).apply()

    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) = sp.edit().putBoolean("keepAlive", v).apply()

    companion object {
        const val DEFAULT_SERVER = "https://api.day.app"
    }
}

/** 一条待推送的消息。 */
data class Outgoing(
    val title: String,
    val subtitle: String,
    val body: String,
    val code: String?,
    val sender: String,
    val time: Long,
) {
    val summary: String get() = code?.let { "验证码 $it" } ?: body.replace('\n', ' ').take(30)

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("subtitle", subtitle).put("body", body)
        .put("code", code).put("sender", sender).put("time", time)

    companion object {
        fun fromJson(o: JSONObject) = Outgoing(
            title = o.getString("title"),
            subtitle = o.optString("subtitle"),
            body = o.getString("body"),
            code = o.optString("code").ifEmpty { null },
            sender = o.optString("sender"),
            time = o.optLong("time"),
        )
    }
}

/** 最近转发记录，只存在本机。 */
object LogStore {
    private const val MAX = 50
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: (() -> Unit)? = null

    data class Entry(val time: Long, val sender: String, val summary: String, val status: String)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("log", Context.MODE_PRIVATE)

    fun add(ctx: Context, sender: String, summary: String, status: String) {
        synchronized(lock) {
            val sp = prefs(ctx)
            val old = JSONArray(sp.getString("entries", "[]"))
            val out = JSONArray().put(
                JSONObject().put("t", System.currentTimeMillis()).put("from", sender)
                    .put("sum", summary).put("st", status)
            )
            for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
            sp.edit().putString("entries", out.toString()).apply()
        }
        main.post { listener?.invoke() }
    }

    fun all(ctx: Context): List<Entry> = synchronized(lock) {
        val arr = JSONArray(prefs(ctx).getString("entries", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optLong("t"), o.optString("from"), o.optString("sum"), o.optString("st"))
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) { prefs(ctx).edit().remove("entries").apply() }
        main.post { listener?.invoke() }
    }
}

/** 断网时发不出去的消息，联网后由 RetryJobService 补发。 */
object PendingStore {
    private val lock = Any()

    data class Item(val id: Long, val msg: Outgoing)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pending", Context.MODE_PRIVATE)

    private fun read(ctx: Context) = JSONArray(prefs(ctx).getString("items", "[]"))

    fun add(ctx: Context, msg: Outgoing) = synchronized(lock) {
        val arr = read(ctx).put(JSONObject().put("id", System.nanoTime()).put("msg", msg.toJson()))
        prefs(ctx).edit().putString("items", arr.toString()).commit()
    }

    fun all(ctx: Context): List<Item> = synchronized(lock) {
        val arr = read(ctx)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Item(o.getLong("id"), Outgoing.fromJson(o.getJSONObject("msg")))
        }
    }

    fun remove(ctx: Context, id: Long) = synchronized(lock) {
        val arr = read(ctx)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("id") != id) out.put(o)
        }
        prefs(ctx).edit().putString("items", out.toString()).commit()
    }

    fun count(ctx: Context) = synchronized(lock) { read(ctx).length() }
}
