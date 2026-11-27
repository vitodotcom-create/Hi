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
            log("— 5б. Какие видеопотоки есть (ищем заднюю камеру) —")
            for (path in STREAM_PATHS) streamInfo(net, gw, 554, path)?.let { log(it) }
        }
        if (80 in open) {
            // MJPEG без привязки: по разбору 70mai (Kino) liveMJPEG вызывается без подписи, ?-sensor=1 — задняя.
            log("— 5м. MJPEG без привязки (возможная задняя) —")
            for (q in listOf("", "?-sensor=0", "?-sensor=1")) log(mjpegProbe(net, gw, q))
            // Без подписи регистратор рвёт HTTP-связь, поэтому сначала привязка (как в приложении 70mai).
            log("— 5. Привязка и команды регистратору (только чтение) —")
            val prefs = ctx.getSharedPreferences("mai70", Context.MODE_PRIVATE)
            val api = Mai70Api(gw) { url -> net.openConnection(url) as java.net.HttpURLConnection }
            api.token = prefs.getString("token", null)
            val myV4 = lp?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            if (api.token != null && !api.call("getdeviceattr.cgi").ok) api.token = null   // старый токен не подходит
            if (api.token == null) {
                val userId = prefs.getString("user", null) ?: Mai70Api.newUserId().also { prefs.edit().putString("user", it).apply() }
                log(api.pair(userId, onNeedButton = { log("Нажмите и подержите кнопку Wi-Fi на регистраторе (жду 30 с)…") }))
                api.token?.let { prefs.edit().putString("token", it).apply() }
            } else log("привязка: есть сохранённый токен")
            if (api.token != null) {
                if (myV4 != null) log("регистрация бокса ($myV4): " + api.register(myV4).code)
                for (cgi in SIGNED_READS) {
                    val r = api.call(cgi)
                    log("$cgi → ${r.code} ${r.body.replace(Regex("\\s+"), " ").replace(Regex("(\"wifikey\"\\s*:\\s*\")[^\"]*"), "$1***").take(170)}")
                }
            }
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

    /**
     * Проба MJPEG-потока (без подписи): GET /cgi-bin/liveMJPEG[query].
     * Отвечает ли регистратор, какой тип, и приходит ли JPEG-кадр (сигнатура FF D8).
     */
    private fun mjpegProbe(net: Network, host: String, query: String): String = try {
        net.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(host, 80), 3000)
            s.soTimeout = 4000
            s.getOutputStream().write(
                "GET /cgi-bin/liveMJPEG$query HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray()
            )
            val inp = s.getInputStream()
            val got = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val end = System.currentTimeMillis() + 3500
            while (System.currentTimeMillis() < end && got.size() < 16384) {
                val k = try { inp.read(buf) } catch (_: Exception) { -1 }
                if (k < 0) break
                got.write(buf, 0, k)
            }
            val bytes = got.toByteArray()
            val head = String(bytes, 0, minOf(bytes.size, 300), Charsets.ISO_8859_1)
            val status = head.lineSequence().firstOrNull()?.trim()?.ifEmpty { "молчит" } ?: "молчит"
            val ctype = Regex("(?i)Content-Type:\\s*([^\r\n]+)").find(head)?.groupValues?.get(1)?.trim() ?: "?"
            val jpeg = (0 until bytes.size - 1).any { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xD8.toByte() }
            "liveMJPEG$query → $status; тип: $ctype; кадр JPEG: ${if (jpeg) "ЕСТЬ ✔" else "нет"}"
        }
    } catch (e: Exception) {
        "liveMJPEG$query → нет ответа (${e.javaClass.simpleName})"
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

    /** DESCRIBE: есть ли поток, его адрес сеанса и размер кадра (из SPS). Нет потока — null (не засоряем экран). */
    private fun streamInfo(net: Network, host: String, port: Int, path: String): String? = try {
        net.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(host, port), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write("DESCRIBE rtsp://$host:$port$path RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n".toByteArray())
            val inp = s.getInputStream().buffered()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && head.length < 8000) { val b = inp.read(); if (b < 0) break; head.append(b.toChar()) }
            val status = head.lineSequence().first().trim()
            if (!status.contains(" 200")) return@use if (path in KEY_PATHS) "$path → $status" else null
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val sdp = String(ByteArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } })
            val base = Regex("(?im)^Content-Base:\\s*(\\S+)").find(head)?.groupValues?.get(1)?.substringAfter("//")?.substringAfter('/') ?: "?"
            val sps = Regex("sprop-parameter-sets=([^,;\\s]+)").find(sdp)?.groupValues?.get(1)
            val size = sps?.let { spsSize(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) } ?: "?"
            val codec = Regex("a=rtpmap:\\d+ ([^/\\s]+)").find(sdp)?.groupValues?.get(1) ?: "?"
            "$path → есть: $codec $size, сеанс /$base"
        }
    } catch (e: Exception) {
        if (path in KEY_PATHS) "$path → нет ответа (${e.javaClass.simpleName})" else null
    }

    /** Ширина×высота из H.264 SPS (минимальный разбор exp-Golomb). */
    private fun spsSize(raw: ByteArray): String? = try {
        // убрать байты-заглушки 0x000003
        val b = java.io.ByteArrayOutputStream(); var z = 0
        for (x in raw) { val v = x.toInt() and 0xff; if (z >= 2 && v == 3) { z = 0; continue }; b.write(v); z = if (v == 0) z + 1 else 0 }
        val d = b.toByteArray(); var pos = 8 // пропустить NAL-заголовок
        fun bit(): Int { val r = (d[pos / 8].toInt() shr (7 - pos % 8)) and 1; pos++; return r }
        fun bits(n: Int): Int { var r = 0; repeat(n) { r = (r shl 1) or bit() }; return r }
        fun ue(): Int { var z2 = 0; while (bit() == 0) z2++; return (1 shl z2) - 1 + bits(z2) }
        fun se(): Int { val k = ue(); return if (k % 2 == 1) (k + 1) / 2 else -(k / 2) }
        val profile = bits(8); bits(16); ue()
        var chroma = 1
        if (profile in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = ue(); if (chroma == 3) bit(); ue(); ue(); bit()
            if (bit() == 1) repeat(if (chroma != 3) 8 else 12) { i -> if (bit() == 1) { var last = 8; var next = 8; repeat(if (i < 6) 16 else 64) { if (next != 0) { next = (last + se() + 256) % 256 }; last = if (next == 0) last else next } } }
        }
        ue(); val poc = ue()
        if (poc == 0) ue() else if (poc == 1) { bit(); se(); se(); repeat(ue()) { se() } }
        ue(); bit()
        val w = (ue() + 1) * 16; val hMap = ue() + 1; val frameMbs = bit()
        val h = (2 - frameMbs) * hMap * 16
        if (frameMbs == 0) bit()
        bit()
        var cl = 0; var cr = 0; var ct = 0; var cb = 0
        if (bit() == 1) { cl = ue(); cr = ue(); ct = ue(); cb = ue() }
        val sx = if (chroma == 0) 1 else 2; val sy = (if (chroma == 1) 2 else 1) * (2 - frameMbs)
        "${w - (cl + cr) * sx}×${h - (ct + cb) * sy}"
    } catch (_: Exception) { null }

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
        /** Где может быть задняя камера (сервер Novatek): печатаем только найденные + главные. */
        private val KEY_PATHS = setOf("/livestream/11", "/livestream/12")
        private val STREAM_PATHS = listOf(
            "/livestream/11", "/livestream/12", "/livestream/13", "/livestream/14",
            "/livestream/21", "/livestream/22", "/livestream/23", "/livestream/24",
            "/livestream/1", "/livestream/2", "/livestream/3", "/livestream/4",
            "/livestream/rear", "/rear", "/live/rear", "/live/1", "/live/2",
            "/stream1", "/stream2", "/ch1", "/ch2", "/cam1", "/cam2", "/channel=1", "/0", "/1", "/2",
        )
        /** Подписанные команды только для чтения (из разбора приложения 70mai в проекте Kino). */
        private val SIGNED_READS = listOf("getdeviceattr.cgi", "getrearstatus.cgi", "getsdstate.cgi", "getdvrstate.cgi", "getgpsstatus.cgi")
        private val RTSP_PATHS = listOf("/", "/livestream/12", "/livestream/11", "/live", "/stream0", "/ch0")
    }
}
