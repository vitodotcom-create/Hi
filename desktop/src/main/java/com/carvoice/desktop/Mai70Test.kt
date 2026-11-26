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
            log("Одновременно 2,4 и 5 ГГц: ${yes(wm.isDualBandSimultaneousSupported)}")
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
        step2(cams.maxBy { it.level }.SSID)
    }

    /** 2. Подключаемся к регистратору как к «местной» сети (интернет должен остаться на телефоне). */
    private fun step2(camSsid: String) {
        if (Build.VERSION.SDK_INT < 29) { log("✖ Нужен Android 10 или новее"); finish(); return }
        log("— 3. Подключаюсь к $camSsid —")
        log("Если появится окно «Подключиться?» — нажмите ПОДКЛЮЧИТЬ.")
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
        log("✔ Подключился к регистратору")
        Thread.sleep(3000)
        log("Интернет с телефона при этом: ${if (internet()) "ОСТАЛСЯ ✔" else "ПРОПАЛ ✖"}")

        val lp = cm.getLinkProperties(net)
        val gw = lp?.routes?.firstOrNull { it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress
            ?: "192.168.0.1"
        val my = lp?.linkAddresses?.firstOrNull()?.address?.hostAddress ?: "?"
        log("Адрес регистратора: $gw   адрес бокса: $my")

        log("— 4. Что открыто на регистраторе —")
        val open = PORTS.filter { port(net, gw, it) }
        log(if (open.isEmpty()) "✖ Ни один порт не ответил" else "Открыты порты: " + open.joinToString { "$it${PORT_NAMES[it]?.let { n -> " ($n)" } ?: ""}" })

        if (80 in open) {
            log("— 5. Команды регистратору (только чтение) —")
            for (p in PATHS) log(http(net, "http://$gw$p"))
        }
        if (554 in open) {
            log("— 6. Видео (RTSP) —")
            log(rtsp(net, gw))
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
        callback?.let { c -> main.post { try { cm.unregisterNetworkCallback(c) } catch (_: Exception) {} } }
        callback = null
        log("Отключился от регистратора, бокс снова только на телефоне.")
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

    private fun rtsp(net: Network, host: String): String = try {
        net.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(host, 554), 2000)
            s.soTimeout = 3000
            s.getOutputStream().write("OPTIONS rtsp://$host:554/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val buf = ByteArray(400)
            val n = s.getInputStream().read(buf)
            if (n > 0) "RTSP ответил: " + String(buf, 0, n).replace(Regex("\\s+"), " ").take(200) else "RTSP молчит"
        }
    } catch (e: Exception) {
        "RTSP → нет ответа (${e.javaClass.simpleName})"
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
        )
    }
}
