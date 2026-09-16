package io.github.ahmedov.prismshare.testspike

import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.ahmedov.prismshare.testspike.bench.DecodeBenchmark
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var tv: TextView
    private lateinit var hotspotButton: Button
    private lateinit var benchButton: Button
    private val sb = StringBuilder()
    private var hotspot: HotspotSpike? = null

    /** Appends a line to the on-screen log. Call on the UI thread. */
    private fun show(s: String) {
        sb.appendLine(s)
        tv.text = sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hotspotButton = Button(this).apply {
            text = "Hotspot spike"
            setOnClickListener { startHotspot() }
        }
        benchButton = Button(this).apply {
            text = "Decode benchmark"
            setOnClickListener { startBenchmark() }
        }
        tv = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 28, 0, 0)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 72, 28, 28)
            addView(hotspotButton)
            addView(benchButton)
            addView(ScrollView(this@MainActivity).apply { addView(tv) })
        })
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        hotspotButton.isEnabled = enabled
        benchButton.isEnabled = enabled
    }

    // The hotspot runs until the app is closed, so both buttons stay disabled.
    private fun startHotspot() {
        setButtonsEnabled(false)
        hotspot = HotspotSpike(this, ::show).also { it.start() }
    }

    private fun startBenchmark() {
        setButtonsEnabled(false)
        thread(name = "prismbench") {
            DecodeBenchmark(applicationContext) { s -> runOnUiThread { show(s) } }.run()
            runOnUiThread { setButtonsEnabled(true) }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == HotspotSpike.REQUEST_CODE) hotspot?.onRequestPermissionsResult()
    }

    override fun onDestroy() {
        hotspot?.close()
        super.onDestroy()
    }
}
