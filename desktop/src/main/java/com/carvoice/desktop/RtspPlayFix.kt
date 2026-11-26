package com.carvoice.desktop

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory

/**
 * Посредник между плеером и регистратором 70mai.
 *
 * Регистратор (сервер Novatek) после DESCRIBE сообщает свой адрес сеанса в Content-Base
 * (rtsp://192.168.0.1/00000001/) и принимает PLAY только по нему, а плеер media3 шлёт PLAY
 * по исходному адресу (/livestream/12) — и получает 404.
 * Посредник пропускает всё как есть, но в PLAY/PAUSE/TEARDOWN/GET_PARAMETER подставляет Content-Base.
 *
 * Плеер подключается к rtsp://127.0.0.1:[port]/…, посредник — к регистратору через [factory]
 * (сокеты сети Wi-Fi регистратора). Видео идёт в том же соединении (RTP поверх TCP).
 */
class RtspPlayFix(
    private val cameraHost: String,
    private val cameraPort: Int = 554,
    private val factory: SocketFactory = SocketFactory.getDefault(),
    private val log: (String) -> Unit = {},
) {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var closed = false
    private val sockets = mutableListOf<Socket>()

    fun start(): RtspPlayFix {
        Thread({
            while (!closed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                Thread({ session(client) }, "rtsp-fix-session").start()
            }
        }, "rtsp-fix-accept").apply { isDaemon = true }.start()
        return this
    }

    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: Exception) {} } }
    }

    private fun session(client: Socket) {
        val cam = try {
            factory.createSocket().apply { connect(InetSocketAddress(cameraHost, cameraPort), 5000); tcpNoDelay = true }
        } catch (e: Exception) {
            log("посредник: нет связи с регистратором (${e.javaClass.simpleName})"); client.close(); return
        }
        client.tcpNoDelay = true
        synchronized(sockets) { sockets += client; sockets += cam }
        val contentBaseRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val camIn = BufferedInputStream(cam.getInputStream())
        val cliIn = BufferedInputStream(client.getInputStream())
        val camOut = cam.getOutputStream()
        val cliOut = client.getOutputStream()

        // Регистратор → плеер: всё как есть, только запоминаем Content-Base.
        Thread({
            try {
                pump(camIn, cliOut) { head ->
                    Regex("(?im)^Content-Base:\\s*(\\S+)").find(head)?.groupValues?.get(1)?.let {
                        contentBaseRef.set(it); log("посредник: адрес сеанса $it")
                    }
                    head
                }
            } catch (_: Exception) {
            } finally { closeBoth(client, cam) }
        }, "rtsp-fix-down").start()

        // Плеер → регистратор: адрес 127.0.0.1 меняем на регистратор, а PLAY и др. — на Content-Base.
        try {
            pump(cliIn, camOut) { head ->
                val line = head.substringBefore("\r\n")
                val parts = line.split(' ')
                if (parts.size < 3 || !parts[2].startsWith("RTSP/")) return@pump head
                val method = parts[0]
                var uri = parts[1].replace(Regex("^rtsp://127\\.0\\.0\\.1(:\\d+)?"), "rtsp://$cameraHost:$cameraPort")
                val base = contentBaseRef.get()
                if (base != null && method in AGGREGATE && !uri.startsWith(base)) uri = base
                if (method in AGGREGATE) log("посредник: $method → $uri")
                "$method $uri ${parts.drop(2).joinToString(" ")}" + head.substring(line.length)
            }
        } catch (_: Exception) {
        } finally { closeBoth(client, cam) }
    }

    private fun closeBoth(a: Socket, b: Socket) {
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
    }

    /**
     * Перекачка потока RTSP: текстовые сообщения (заголовки + тело по Content-Length) проходят через
     * [edit], двоичные кадры «$ канал длина данные» (видео) — без изменений.
     */
    private fun pump(inp: InputStream, out: OutputStream, edit: (String) -> String) {
        while (true) {
            inp.mark(1)
            val first = inp.read()
            if (first < 0) return
            if (first == '$'.code) {
                val ch = inp.read(); val hi = inp.read(); val lo = inp.read()
                if (lo < 0) return
                val len = (hi shl 8) or lo
                val data = readFully(inp, len) ?: return
                synchronized(out) {
                    out.write(byteArrayOf('$'.code.toByte(), ch.toByte(), hi.toByte(), lo.toByte()))
                    out.write(data); out.flush()
                }
                continue
            }
            inp.reset()
            val head = readHead(inp) ?: return
            val len = Regex("(?im)^Content-Length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
            val body = if (len > 0) readFully(inp, len) ?: return else ByteArray(0)
            val edited = edit(head)
            synchronized(out) { out.write(edited.toByteArray(Charsets.ISO_8859_1)); out.write(body); out.flush() }
        }
    }

    private fun readHead(inp: InputStream): String? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read(); if (b < 0) return null
            sb.append(b.toChar())
            if (sb.length > 16384) return null
        }
        return sb.toString()
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n); var r = 0
        while (r < n) { val k = inp.read(buf, r, n - r); if (k < 0) return null; r += k }
        return buf
    }

    companion object {
        private val AGGREGATE = setOf("PLAY", "PAUSE", "TEARDOWN", "GET_PARAMETER", "SET_PARAMETER")
    }
}
