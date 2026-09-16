package io.github.ahmedov.prismshare.testspike

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Wi-Fi local-only hotspot throughput spike, moved out of MainActivity unchanged.
 * Logs under SPIKE. [show] appends a line to the screen and is called on the UI thread.
 */
class HotspotSpike(private val activity: Activity, private val show: (String) -> Unit) {

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val totalBytes = 200L * 1024 * 1024      // 200 MB
    private var serverStarted = false

    private fun log(s: String) = activity.runOnUiThread {
        show(s)
        android.util.Log.i("SPIKE", s)
    }

    fun start() {
        log("Device  ${Build.MANUFACTURER} ${Build.MODEL}")
        log("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("")
        log("BEFORE hotspot:")
        log("  active network: ${activeTransport()}")
        listInterfaces()
        log("")

        val need = missing()
        if (need.isEmpty()) go()
        else {
            log("Requesting: ${need.joinToString { it.substringAfterLast('.') }}")
            ActivityCompat.requestPermissions(activity, need, REQUEST_CODE)
        }
    }

    // ------------------------------------------------------------ permissions

    private fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun missing(): Array<String> = required().filter {
        ActivityCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun onRequestPermissionsResult() {
        val still = missing()
        if (still.isEmpty()) go()
        else log("DENIED: ${still.joinToString { it.substringAfterLast('.') }}\n" +
                "Grant in Settings › Apps › this app › Permissions, then reopen.")
    }

    private fun go() {
        startServer()
        startHotspot()
    }

    // ------------------------------------------------------------ diagnostics

    private fun activeTransport(): String = try {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI  ← phone still joined to a network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "other"
        }
    } catch (e: Exception) { "unknown (${e.message})" }

    private fun interfaces(): List<Pair<String, List<String>>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .map { nif ->
                nif.name to nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
            }
            .filter { it.second.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    private fun listInterfaces() {
        val list = interfaces()
        if (list.isEmpty()) log("  (no active IPv4 interfaces)")
        list.forEach { (name, addrs) ->
            val tag = if (looksLikeAp(name)) "  ← soft-AP" else ""
            log("  iface ${name.padEnd(10)} ${addrs.joinToString()}$tag")
        }
    }

    private fun looksLikeAp(name: String) =
        name.startsWith("ap") || name.startsWith("swlan") ||
                name.startsWith("softap") || name == "wlan1"

    private fun ifaceNameFor(addr: String?): String =
        interfaces().firstOrNull { addr in it.second }?.first ?: "?"

    private fun apAddress(): String? {
        val list = interfaces()
        val ap = list.firstOrNull { looksLikeAp(it.first) }
        return (ap ?: list.firstOrNull())?.second?.firstOrNull()
    }

    // --------------------------------------------------------------- hotspot

    private fun startHotspot() {
        val wm = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
        log("Starting local-only hotspot…")
        log("(if nothing happens, check Location services are ON)")
        log("")
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {

                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val cfg = res.softApConfiguration
                    log("  SSID        ${cfg.ssid ?: "(null)"}")
                    log("  PASSPHRASE  ${cfg.passphrase ?: "(null)"}")
                    thread {
                        Thread.sleep(2500)
                        log("")
                        log("AFTER hotspot:")
                        log("  active network: ${activeTransport()}")
                        listInterfaces()
                        val ip = apAddress() ?: "192.168.43.1"
                        log("")
                        log("  best guess AP address: $ip  (iface ${ifaceNameFor(ip)})")
                        log("")
                        log("Join the network above on your laptop, then run:")
                        log("  curl -o /dev/null http://$ip:8080/f")
                        log("")
                    }
                }

                override fun onFailed(reason: Int) = log(
                    "FAILED — reason $reason " + when (reason) {
                        0 -> "(NO_CHANNEL — location off, or radio busy)"
                        1 -> "(GENERIC)"
                        2 -> "(INCOMPATIBLE_MODE — another hotspot/tether active)"
                        3 -> "(TETHERING_DISALLOWED — carrier or policy block)"
                        else -> ""
                    }
                )

                override fun onStopped() { log("Hotspot stopped.") }

            }, null)
        } catch (e: SecurityException) {
            log("SecurityException: ${e.message}")
        } catch (e: Exception) {
            log("Exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- server

    private fun startServer() {
        if (serverStarted) return
        serverStarted = true
        thread(isDaemon = true) {
            try {
                val server = ServerSocket(8080)
                log("Server listening on port 8080 · payload ${totalBytes / 1024 / 1024} MB")
                while (true) {
                    val sock = server.accept()
                    thread { serve(sock) }
                }
            } catch (e: Exception) {
                log("Server error: ${e.message}")
            }
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val remote = sock.inetAddress?.hostAddress
            val local = sock.localAddress?.hostAddress
            val iface = ifaceNameFor(local)

            log("")
            log("→ client        $remote")
            log("  arrived on    $local  (iface $iface)")
            log(if (looksLikeAp(iface))
                "  ✓ ROUTE OK — this connection came in over the hotspot"
            else
                "  ⚠ WARNING — iface '$iface' does not look like the soft-AP.\n" +
                        "     The transfer may be going over your normal network.\n" +
                        "     Turn off mobile data, disconnect the phone from Wi-Fi, retry.")
            log("")

            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                        "Content-Length: $totalBytes\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
            )
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            var nextMark = 50L * 1024 * 1024
            val t0 = System.nanoTime()
            while (sent < totalBytes) {
                val n = minOf(buf.size.toLong(), totalBytes - sent).toInt()
                out.write(buf, 0, n)
                sent += n
                if (sent >= nextMark) {
                    log("   … ${sent / 1024 / 1024} MB")
                    nextMark += 50L * 1024 * 1024
                }
            }
            out.flush()
            val secs = (System.nanoTime() - t0) / 1e9
            log("")
            log("✓ %d MB in %.1f s  →  %.2f MB/s   [via iface %s]".format(
                totalBytes / 1024 / 1024, secs, totalBytes / 1048576.0 / secs, iface))
            log("")
        } catch (e: Exception) {
            log("✗ transfer ended early: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        reservation?.close()
    }

    companion object {
        const val REQUEST_CODE = 1
    }
}
