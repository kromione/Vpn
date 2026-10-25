package com.ali.hizlivpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class Server(val host: String, val country: String, val speed: Long, val config: String, var latency: Long = -1)

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var servers = listOf<Server>()
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private var loop: Job? = null
    private var busy = false
    private val cacheFile by lazy { File(filesDir, "servers.json") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        status = TextView(this).apply { textSize = 15f }
        val btn = Button(this).apply { text = "Şimdi yenile"; setOnClickListener { scope.launch { refresh() } } }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, i, _ -> servers.getOrNull(i)?.let { connect(it) } }
        }
        root.addView(status); root.addView(btn); root.addView(list, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        servers = loadCache(); show()
    }

    // Uygulama açıkken her 3 dakikada bir tüm sunucuları yeniden test eder
    override fun onStart() { super.onStart(); loop = scope.launch { while (isActive) { refresh(); delay(3 * 60_000L) } } }
    override fun onStop() { loop?.cancel(); super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun refresh() {
        if (busy) return
        busy = true
        status.text = "Sunucu listesi alınıyor…"
        try {
            val fetched = withContext(Dispatchers.IO) { fetch() }
            status.text = "${fetched.size} sunucu test ediliyor…"
            val alive = withContext(Dispatchers.IO) {
                fetched.map { s -> async { s.latency = probe(s.config); s } }.awaitAll()
            }.filter { it.latency >= 0 }.sortedWith(compareBy<Server> { it.latency }.thenByDescending { it.speed })
            if (alive.isNotEmpty()) { servers = alive; saveCache(alive) }
            status.text = "Çalışan: ${alive.size} • Son kontrol: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}\nBağlanmak için sunucuya dokun."
        } catch (e: Exception) {
            status.text = "Hata: ${e.message}\nSon çalışan liste gösteriliyor."
        }
        show(); busy = false
    }

    private fun fetch(): List<Server> {
        val c = URL("http://www.vpngate.net/api/iphone/").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("*") && !it.startsWith("#") }
            .mapNotNull { line ->
                val p = line.split(",")
                if (p.size < 15) null else runCatching {
                    Server(p[0], p[5], p[4].toLongOrNull() ?: 0, String(Base64.decode(p[14], Base64.DEFAULT)))
                }.getOrNull()
            }.toList()
    }

    // OpenVPN TCP portuna gerçek bağlantı açıp gecikmeyi ölçer; ulaşılamazsa -1
    private fun probe(cfg: String): Long {
        val lines = cfg.lines().map { it.trim() }
        val proto = lines.firstOrNull { it.startsWith("proto ") }?.split(Regex("\\s+"))?.getOrNull(1) ?: "udp"
        if (!proto.startsWith("tcp")) return -1
        val r = lines.firstOrNull { it.startsWith("remote ") }?.split(Regex("\\s+")) ?: return -1
        val host = r.getOrNull(1) ?: return -1
        val port = r.getOrNull(2)?.toIntOrNull() ?: 1194
        return try {
            val t = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
            (System.nanoTime() - t) / 1_000_000
        } catch (e: Exception) { -1 }
    }

    private fun show() {
        adapter.clear()
        adapter.addAll(servers.map { "${it.country} • ${it.latency} ms • ${it.speed / 1_000_000} Mbps\n${it.host}" })
    }

    private fun saveCache(list: List<Server>) = runCatching {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("h", it.host).put("c", it.country).put("s", it.speed).put("l", it.latency).put("cfg", it.config)) }
        cacheFile.writeText(a.toString())
    }

    private fun loadCache(): List<Server> = runCatching {
        val a = JSONArray(cacheFile.readText())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Server(o.getString("h"), o.getString("c"), o.getLong("s"), o.getString("cfg"), o.getLong("l")) } }
    }.getOrDefault(emptyList())

    // Seçilen sunucunun .ovpn profilini OpenVPN uygulamasına aktarır
    private fun connect(s: Server) {
        val dir = File(cacheDir, "vpn").apply { mkdirs() }
        val f = File(dir, "${s.host}.ovpn").apply { writeText(s.config) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-openvpn-profile")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(view) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Önce 'OpenVPN for Android' uygulamasını kur", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=de.blinkt.openvpn"))) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=de.blinkt.openvpn"))) }
        }
    }
}
