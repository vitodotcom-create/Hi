package com.carvoice.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/**
 * Проверка: можно ли работать с регистратором 70mai, не теряя интернет с телефона.
 * Только читает (ничего в регистраторе не меняет). Каждую строку отдаёт в [log].
 */
class Mai70Test(private val ctx: Context, private val log: (String) -> Unit) {

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var finished = false

    @SuppressLint("MissingPermission")
    fun start() {
        safeThread { step1() }
    }

    /** 1. Что умеет Wi-Fi бокса и есть ли интернет сейчас. */
    @SuppressLint("MissingPermission")
    private fun step1() {
        log("— 1. Wi-Fi бокса —")
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        val ssid = info?.ssid?.trim('"').orEmpty()
        log("Сейчас подключён к: ${if (ssid.isBlank() || ssid == "<unknown ssid>") "?" else ssid}" +
            (info?.frequency?.takeIf { it > 0 }?.let { "  (${if (it > 4900) "5 ГГц" else "2,4 ГГц"})" } ?: ""))
        log("Интернет сейчас: ${if (internet()) "есть ✔" else "нет ✖"}")
        if (Build.VERSION.SDK_INT >= 31) {
            log("Два Wi-Fi сразу (местная сеть): ${yes(wm.isStaConcurrencyForLocalOnlyConnectionsSupported)}")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            log("Два Wi-Fi сразу (оба с интернетом): ${yes(wm.isStaConcurrencyForMultiInternetSupported)}")
        }

        // Wi-Fi бокса уже на регистраторе (интернет идёт по Bluetooth) — работаем через него, ничего не переключаем.
        if (ssid.contains("70mai", true)) {
            @Suppress("DEPRECATION")
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            if (wifi != null) { step3(wifi); return }
        }

        log("— 2. Ищу регистратор —")
        @Suppress("DEPRECATION")
        wm.startScan()
        Thread.sleep(4000)
        val cams = wm.scanResults.filter { it.SSID.contains("70mai", true) }
        if (cams.isEmpty()) {
            log("✖ Сеть 70mai не видна. Включите Wi-Fi на регистраторе (в приложении 70mai или в меню регистратора) и нажмите ТЕСТ ещё раз.")
            finish(); return
        }
        cams.forEach { log("Нашёл: ${it.SSID}  (${if (it.frequency > 4900) "5 ГГц" else "2,4 ГГц"}, сигнал ${it.level} дБм)") }
        // Рядом бывает чужой регистратор (70mai_A800S_5G_…) — берём свой A810.
        step2((cams.filter { it.SSID.contains("A810", true) }.ifEmpty { cams }).maxBy { it.level }.SSID)
    }

    /** 2. Подключаемся к регистратору как к «местной» сети (интернет должен остаться на телефоне). */
    private fun step2(camSsid: String) {
        if (Build.VERSION.SDK_INT < 29) { log("✖ Нужен Android 10 или новее"); finish(); return }
        log("— 3. Подключаюсь к $camSsid —")
        log("Если появится окно «Подключиться?» — нажмите ПОДКЛЮЧИТЬ.")
        log("Интернет на время теста может пропасть — это нормально, потом вернётся.")
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(camSsid, PatternMatcher.PATTERN_LITERAL))
            .setWpa2Passphrase(PASSWORD)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                safeThread { step3(network) }
            }
            override fun onUnavailable() {
                log("✖ Не подключился. Возможно, пароль Wi-Fi регистратора не $PASSWORD — пришлите мне его из приложения 70mai.")
                finish()
            }
        }
        callback = cb
        main.post {
            try {
                cm.requestNetwork(req, cb, 60_000)
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    /** 3. Регистратор подключён: проверяем интернет и что регистратор отвечает. */
    private fun step3(net: Network) {
        if (finished) return
        log("✔ Связь с регистратором есть")
        if (callback != null) Thread.sleep(3000)
        log("Интернет при этом: ${if (internet()) "ЕСТЬ ✔" else "НЕТ ✖"}")

        val lp = cm.getLinkProperties(net)
        val gw = lp?.routes?.firstOrNull { it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress
            ?: "192.168.0.1"
        val my = lp?.linkAddresses?.firstOrNull()?.address?.hostAddress ?: "?"
        log("Адрес регистратора: $gw   адрес бокса: $my")

        log("— 4. Что открыто на регистраторе —")
        val open = PORTS.filter { port(net, gw, it) }
        log(if (open.isEmpty()) "✖ Ни один порт не ответил" else "Открыты порты: " + open.joinToString { "$it${PORT_NAMES[it]?.let { n -> " ($n)" } ?: ""}" })

        // Сначала подробно разбираем видео: HTTP-команды ниже могут оборвать связь.
        if (554 in open) {
            log("— 5а. Видео подробно (/livestream/12) —")
            rtspDeep(net, gw, 554, "/livestream/12").forEach { log(it) }
        }
        if (80 in open) {
            log("— 5. Команды регистратору (только чтение) —")
            for (p in PATHS) log(http(net, "http://$gw$p"))
        }
        for (p in open.filter { it != 80 && it != 21 && it != 23 && it != 554 && it != 8554 }) {
            log(http(net, "http://$gw:$p/"))
        }
        for (p in open.filter { it == 554 || it == 8554 }) {
            log("— 6. Видео (RTSP, порт $p) —")
            for (path in RTSP_PATHS) log(rtsp(net, gw, p, path))
        }
        log("— Готово. Сфотографируйте этот экран и пришлите мне —")
        finish()
    }

    /** Любая ошибка — строкой в окно, а не падением стола. */
    private fun safeThread(body: () -> Unit) = Thread {
        try {
            body()
        } catch (e: Throwable) {
            fail(e)
        }
    }.start()

    private fun fail(e: Throwable) {
        log("✖ Ошибка: ${e.javaClass.simpleName}: ${e.message}")
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        callback?.let { c ->
            main.post { try { cm.unregisterNetworkCallback(c) } catch (_: Exception) {} }
            log("Отключился от регистратора, бокс снова только на телефоне.")
        }
        callback = null
    }

    fun cancel() = finish()

    private fun yes(b: Boolean) = if (b) "ДА ✔" else "нет ✖"

    /** Есть ли интернет через основную сеть (телефон). */
    private fun internet(): Boolean = try {
        val c = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        val ok = c.responseCode in 200..399
        c.disconnect(); ok
    } catch (_: Exception) {
        false
    }

    private fun port(net: Network, host: String, p: Int): Boolean = try {
        net.socketFactory.createSocket().use { it.connect(InetSocketAddress(host, p), 1500); true }
    } catch (_: Exception) {
        false
    }

    private fun http(net: Network, url: String): String = try {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        val clean = body.replace(Regex("\\s+"), " ").replace(Regex("(\"wifikey\"\\s*:\\s*\")[^\"]*"), "$1***")
        "${url.substringAfter("cgi-bin/")} → $code ${clean.take(160)}"
    } catch (e: Exception) {
        "${url.substringAfter("cgi-bin/")} → нет ответа (${e.javaClass.simpleName})"
    }

    /** DESCRIBE: 200 — видео по этому адресу есть, 404 — нет. Печатаем только первую строку ответа. */
    private fun rtsp(net: Network, host: String, port: Int, path: String): String = try {
        net.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write(
                "DESCRIBE rtsp://$host:$port$path RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n".toByteArray()
            )
            val buf = ByteArray(400)
            val n = s.getInputStream().read(buf)
            "$path → " + if (n > 0) String(buf, 0, n).lineSequence().first().trim() else "молчит"
        }
    } catch (e: Exception) {
        "$path → нет ответа (${e.javaClass.simpleName})"
    }

    /**
     * Видео по шагам, как его просит плеер: DESCRIBE → SETUP → PLAY.
     * Плеер получал «PLAY 404» — смотрим, по какому адресу регистратор ждёт PLAY.
     */
    private fun rtspDeep(net: Network, host: String, port: Int, path: String): List<String> {
        val out = mutableListOf<String>()
        try {
            net.socketFactory.createSocket().use { s ->
                s.connect(InetSocketAddress(host, port), 2000)
                s.soTimeout = 4000
                val inp = s.getInputStream().buffered()
                var cseq = 1
                fun req(method: String, uri: String, extra: String = ""): Pair<String, String> {
                    s.getOutputStream().write("$method $uri RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: CarVoice\r\n$extra\r\n".toByteArray())
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = inp.read(); if (b < 0) break
                        head.append(b.toChar())
                    }
                    val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    val body = ByteArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } }
                    return head.toString() to String(body)
                }
                fun hdr(h: String, name: String) = Regex("(?im)^$name:\\s*(.+)$").find(h)?.groupValues?.get(1)?.trim()
                val url = "rtsp://$host:$port$path"
                val (dh, sdp) = req("DESCRIBE", url, "Accept: application/sdp\r\n")
                out += "DESCRIBE → ${dh.lineSequence().first().trim()}"
                hdr(dh, "Content-Base")?.let { out += "Content-Base: $it" }
                hdr(dh, "Content-Location")?.let { out += "Content-Location: $it" }
                sdp.lineSequence().map { it.trim() }
                    .filter { it.startsWith("m=") || it.startsWith("a=control") || it.startsWith("a=rtpmap") || it.startsWith("a=range") }
                    .take(8).forEach { out += "SDP $it" }
                val base = hdr(dh, "Content-Base") ?: url
                val ctrl = sdp.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("m=video") }
                    .firstOrNull { it.startsWith("a=control:") }?.removePrefix("a=control:")
                val track = when {
                    ctrl == null || ctrl == "*" -> base
                    ctrl.startsWith("rtsp://") -> ctrl
                    else -> base.trimEnd('/') + "/" + ctrl
                }
                val (sh, _) = req("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
                out += "SETUP $track → ${sh.lineSequence().first().trim()}"
                val session = hdr(sh, "Session")?.substringBefore(';')
                hdr(sh, "Transport")?.let { out += "Transport: $it" }
                if (session == null) { out += "нет Session — PLAY не пробую"; return out }
                for (u in listOf(url, "$url/", base, track, "*").distinct()) {
                    val (ph, _) = req("PLAY", u, "Session: $session\r\nRange: npt=0.000-\r\n")
                    val line = ph.lineSequence().first().trim()
                    out += "PLAY $u → $line"
                    if (line.contains(" 200")) break
                }
                req("TEARDOWN", url, "Session: $session\r\n")
            }
        } catch (e: Exception) {
            out += "обрыв: ${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"
        }
        return out
    }

    companion object {
        /** Пароль Wi-Fi 70mai по умолчанию. */
        private const val PASSWORD = "12345678"
        private val PORTS = listOf(21, 23, 80, 443, 554, 8080, 8554, 3333, 6666, 7777, 8192)
        private val PORT_NAMES = mapOf(21 to "файлы", 23 to "telnet", 80 to "команды", 554 to "видео", 8554 to "видео")
        private val PATHS = listOf(
            "/cgi-bin/getdeviceattr.cgi",
            "/cgi-bin/getsdstate.cgi",
            "/cgi-bin/getfilecount.cgi",
            "/cgi-bin/getwifi.cgi",
            "/cgi-bin/getAllMenu.cgi",
            "/",
        )
        private val RTSP_PATHS = listOf("/", "/livestream/12", "/livestream/11", "/live", "/stream0", "/ch0")
    }
}
