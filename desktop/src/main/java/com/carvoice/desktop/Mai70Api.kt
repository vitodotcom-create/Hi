package com.carvoice.desktop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Команды регистратору 70mai A810 по HTTP (http://192.168.0.1/cgi-bin/имя.cgi).
 *
 * Без подписи регистратор рвёт соединение. Порядок взят из открытого просмотрщика A810
 * (GitHub ClanSlum/Kino, разбор приложения 70mai):
 *  1. Привязка: BindByBanya.cgi?-usr=<id>&-signkey=md5(id + MAGIC) → токен (или timestamp для подтверждения);
 *  2. если токена нет: UserconfirmByBanya.cgi?-timestamp=<ts>&-signkey=md5(ts + MAGIC)
 *     (на части прошивок перед этим нужно нажать кнопку Wi-Fi на регистраторе);
 *  3. client.cgi?-operation=register&-ip=<адрес бокса> (подписано);
 *  4. любая команда: <cgi>?<параметры>&-source=wifi&-timestamp=<ts>&-signkey=md5("<cgi>?<параметры>&-source=wifi&-timestamp=<ts>" + токен).
 *
 * [open] открывает соединение (на боксе — через сеть Wi-Fi регистратора).
 */
class Mai70Api(
    private val host: String = "192.168.0.1",
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    var token: String? = null

    data class Reply(val ok: Boolean, val code: String, val body: String, val json: JSONObject?)

    /** Привязка. Возвращает текст для журнала; при успехе заполняет [token]. */
    fun pair(userId: String, onNeedButton: () -> Unit = {}, waitMs: Long = 30_000): String {
        val bind = get("BindByBanya.cgi?-usr=$userId&-signkey=${md5(userId + MAGIC)}")
        if (!bind.ok) return "привязка: ${bind.code} ${bind.body.take(120)}"
        val r = bind.json?.optJSONObject("Result")
        r?.let { pick(it, "Token", "token") }?.let { token = it; return "привязка: токен получен сразу" }
        val ts = r?.let { pick(it, "timestamp", "Timestamp") } ?: bind.json?.optString("timestamp")?.takeIf { it.isNotEmpty() }
            ?: return "привязка: ни токена, ни timestamp: ${bind.body.take(120)}"
        // Подтверждение: сначала без кнопки, потом просим нажать кнопку Wi-Fi и пробуем ещё.
        val end = System.currentTimeMillis() + waitMs
        var asked = false
        var last = ""
        while (true) {
            val c = get("UserconfirmByBanya.cgi?-timestamp=$ts&-signkey=${md5(ts + MAGIC)}")
            val cr = c.json?.optJSONObject("Result")
            val t = cr?.let { pick(it, "token", "Token", "bindkey") } ?: c.json?.let { pick(it, "token", "bindkey") }
            if (c.ok && t != null) { token = t; return "привязка: подтверждено, токен получен" }
            last = "${c.code} ${c.body.take(80)}"
            if (System.currentTimeMillis() > end) return "привязка: не подтверждена ($last)"
            if (!asked) { asked = true; onNeedButton() }
            Thread.sleep(2000)
        }
    }

    /** Регистрация бокса как клиента (нужна после привязки). */
    fun register(myIp: String): Reply = call("client.cgi", "-operation=register&-ip=$myIp")

    /** Подписанная команда. [params] — без -source/-timestamp/-signkey, например "-enable=1". */
    fun call(cgi: String, params: String = ""): Reply {
        val t = token ?: return Reply(false, "нет токена", "", null)
        val p = (if (params.isEmpty()) "" else "$params&") + "-source=wifi"
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = md5("$cgi?$p&-timestamp=$ts$t")
        return get("$cgi?$p&-timestamp=$ts&-signkey=$sign")
    }

    private fun get(pathAndQuery: String): Reply = try {
        val c = open(URL("http://$host/cgi-bin/$pathAndQuery"))
        c.connectTimeout = 4000; c.readTimeout = 5000
        c.setRequestProperty("Cache-Control", "no-cache")
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val rc = json?.opt("ResultCode")?.toString()
        Reply(code == 200 && rc == "0", rc?.let { "код $it${ERRORS[it]?.let { e -> " ($e)" } ?: ""}" } ?: "HTTP $code", body, json)
    } catch (e: Exception) {
        Reply(false, "нет ответа (${e.javaClass.simpleName})", "", null)
    }

    private fun pick(o: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.takeIf { it.isNotEmpty() && it != "null" } }

    companion object {
        /** Секрет привязки из приложения 70mai (по разбору в проекте Kino). */
        private const val MAGIC = "73VpsAfdety8FDd0"
        private val ERRORS = mapOf("-6677" to "нужна привязка", "-6678" to "неверная подпись", "-6679" to "токен устарел")

        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun newUserId(): String = (1..16).map { "0123456789abcdef".random() }.joinToString("")
    }
}
