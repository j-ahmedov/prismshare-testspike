package io.github.ahmedov.prismshare.testspike.bench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Decode benchmark: how many frames per second this phone classifies in Kotlin.
 * Logs under PRISMBENCH.
 *
 * Classification only. Reed-Solomon is not run: the whitening, interleaving and
 * GF(2^8) conventions needed to form codewords are not in the bundle.
 */
class DecodeBenchmark(private val context: Context, private val show: (String) -> Unit) {

    private fun log(s: String) {
        Log.i(TAG, s)
        show(s)
    }

    private class Stopped : Exception()

    private fun stop(msg: String): Nothing {
        msg.lines().forEach { log(it) }
        throw Stopped()
    }

    private class Result(
        val folder: String,
        val singleMs: Double,
        val singleFps: Double,
        val singleBps: Double,
        val poolMs: Double,
        val poolFps: Double,
        val poolBps: Double,
    )

    fun run() {
        if (!running.compareAndSet(false, true)) {
            log("Decode benchmark is already running.")
            return
        }
        try {
            runAll()
        } catch (_: Stopped) {
            log("Stopped.")
        } catch (e: Throwable) {
            log("✗ ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "benchmark failed", e)
        } finally {
            running.set(false)
        }
    }

    private fun runAll() {
        val cores = Runtime.getRuntime().availableProcessors()
        log("════ PRISMBENCH decode benchmark ════")
        log("CLASSIFICATION ONLY: Reed-Solomon is EXCLUDED (not run).")
        log("Headline figures are therefore ~10% optimistic.")
        log("")
        log("Device   ${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            log("SoC      ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        log("Android  ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("Cores    $cores (availableProcessors)")
        log("Heap     ${Runtime.getRuntime().maxMemory() / 1048576} MB max")
        log("Thermal  ${thermalStatus()} at start")
        log("")

        val benchDir = File(context.getExternalFilesDir(null) ?: stop(pushMessage("app storage unavailable")), "bench")
        if (!File(benchDir, "manifest.json").canRead()) {
            // The app's own files/ dir is always listable, so this tells "absent" from "unreadable".
            val present = benchDir.parentFile?.list()?.contains(benchDir.name) == true
            stop(pushMessage(
                if (present) "is at ${benchDir.path} but this app cannot read it (adb-created folders are owned by shell)"
                else "not found at ${benchDir.path}"
            ))
        }
        val manifest = try {
            loadManifest(benchDir)
        } catch (e: MissingBundleFileException) {
            stop(pushMessage(
                "incomplete or unreadable: cannot read ${e.file.path}"
            ))
        } catch (e: BundleException) {
            stop("✗ Bench bundle problem: ${e.message}")
        }
        log("Bundle   ${benchDir.path}")
        log("         frame format v${manifest.frameFormatVersion}, ${manifest.frameWidth}x${manifest.frameHeight}, " +
                "configurations: ${manifest.configurations.joinToString { it.folder }}")

        val results = manifest.configurations.map { runConfiguration(it, manifest, cores) }

        log("")
        log("════ SUMMARY (classification only, RS excluded, ~10% optimistic) ════")
        log("KB = 1000 bytes (the libcimbar convention); KiB = 1024 bytes")
        for (r in results) {
            log("%-10s 1 thread : %7.2f ms/frame  %6.1f fps  %8.0f KB/s  %8.0f KiB/s".format(
                r.folder, r.singleMs, r.singleFps, r.singleBps / 1000, r.singleBps / 1024))
            log("%-10s %2d threads: %7.2f ms/frame  %6.1f fps  %8.0f KB/s  %8.0f KiB/s".format(
                "", cores, r.poolMs, r.poolFps, r.poolBps / 1000, r.poolBps / 1024))
        }
        log("Thermal  ${thermalStatus()} at end")
        log("Done.")
    }

    private fun pushMessage(problem: String): String {
        val dest = "/sdcard/Android/data/${context.packageName}/files/bench"
        return """
            |Bench bundle $problem.
            |Push the bundle, make it readable by the app, then press "Decode benchmark" again:
            |  adb shell mkdir -p $dest
            |  adb push data/bench/manifest.json data/bench/c1_px4 data/bench/c16_px4 $dest/
            |  adb shell chmod -R o+rX $dest
        """.trimMargin()
    }

    private fun runConfiguration(cfg: Configuration, manifest: Manifest, cores: Int): Result {
        log("")
        log("──── ${cfg.folder} ────")

        // ---------------------------------------------------------------- setup
        val params = try { JSONObject(cfg.params.readText()) } catch (e: Exception) { stop("✗ ${cfg.params.path}: ${e.message}") }
        log("params   colour_depth ${params.opt("colour_depth")}, cell_px ${params.opt("cell_px")}, " +
                "RS(${params.opt("ecc_total")},${params.opt("ecc_data")}) (not run)")

        var t = System.nanoTime()
        val codec = try { loadCodec(cfg.codec, manifest) } catch (e: BundleException) { stop("✗ Bench bundle problem: ${e.message}") }
        log("codec    ${codec.nCells} cells, ${codec.glyphCount} glyphs ${codec.glyphWidth}x${codec.glyphHeight} " +
                "(${codec.inkPerGlyph} ink px), ${codec.paletteR.size} colours; parsed in ${ms(System.nanoTime() - t)} ms")
        t = System.nanoTime()
        val truth = try { loadGroundTruth(cfg, codec) } catch (e: BundleException) { stop("✗ Bench bundle problem: ${e.message}") }
        log("truth    ${cfg.frames.size} frames; parsed in ${ms(System.nanoTime() - t)} ms")
        if (cfg.frames.size < WORKING_SET) stop("✗ ${cfg.folder} has ${cfg.frames.size} frames, need at least $WORKING_SET")

        // ------------------------------------- load, verify, check every frame
        // Each PNG is SHA-256 checked, decoded, classified and compared with ground
        // truth (untimed), then released unless it belongs to the working set.
        val decoder = FrameDecoder(codec)
        val workingSet = ArrayList<Bitmap>(WORKING_SET)
        var readNs = 0L; var shaNs = 0L; var pngNs = 0L
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val sha = MessageDigest.getInstance("SHA-256")
        for ((k, file) in cfg.frames.withIndex()) {
            var t0 = System.nanoTime()
            val bytes = file.readBytes()
            var t1 = System.nanoTime(); readNs += t1 - t0
            val hex = sha.digest(bytes).joinToString("") { "%02x".format(it) }
            t0 = System.nanoTime(); shaNs += t0 - t1
            if (hex != truth.sha256[k])
                stop("✗ ${file.path}: SHA-256 mismatch (${bytes.size} bytes).\n" +
                        "  The push was truncated or corrupted. Push the bundle again.")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: stop("✗ ${file.path}: PNG failed to decode despite a matching SHA-256")
            t1 = System.nanoTime(); pngNs += t1 - t0
            if (bmp.width != codec.frameWidth || bmp.height != codec.frameHeight)
                stop("✗ ${file.path}: ${bmp.width}x${bmp.height}, codec.json says ${codec.frameWidth}x${codec.frameHeight}")

            decoder.decode(bmp)
            checkSymbols(decoder, truth, k, cfg, codec, "load check")
            if (k < WORKING_SET) workingSet.add(bmp) else bmp.recycle()
        }
        val nFrames = cfg.frames.size
        log("verified ${nFrames} PNGs against SHA-256; all ${nFrames * codec.nCells * 2L} symbols match ground truth")
        log("HARNESS OVERHEAD (not in any figure below):")
        log("  PNG decode        %7.2f ms/frame (mean of %d)".format(nsToMs(pngNs) / nFrames, nFrames))
        log("  file read         %7.2f ms/frame".format(nsToMs(readNs) / nFrames))
        log("  SHA-256           %7.2f ms/frame".format(nsToMs(shaNs) / nFrames))
        log("working set: frames 0-${WORKING_SET - 1} held as ARGB_8888 bitmaps")

        // ---------------------------------------------------- single-threaded
        for (i in 0 until WARMUP_FRAMES) decoder.decode(workingSet[i % WORKING_SET])

        val n = SINGLE_FRAMES
        val readT = LongArray(n); val gatherT = LongArray(n); val glyphT = LongArray(n); val colourT = LongArray(n)
        var checkNs = 0L
        val gcBefore = gcCount()
        for (i in 0 until n) {
            val bmp = workingSet[i % WORKING_SET]
            val t0 = System.nanoTime()
            decoder.readPixels(bmp)
            val t1 = System.nanoTime()
            decoder.gatherCells()
            val t2 = System.nanoTime()
            decoder.classifyGlyphs()
            val t3 = System.nanoTime()
            decoder.classifyColours()
            val t4 = System.nanoTime()
            readT[i] = t1 - t0; gatherT[i] = t2 - t1; glyphT[i] = t3 - t2; colourT[i] = t4 - t3
            // Outside the timed region: t4 is already taken.
            checkSymbols(decoder, truth, i % WORKING_SET, cfg, codec, "single-threaded run, timed frame $i")
            checkNs += System.nanoTime() - t4
        }
        val gcSingle = gcCount() - gcBefore
        val totalT = LongArray(n) { readT[it] + gatherT[it] + glyphT[it] + colourT[it] }
        val singleMs = meanMs(totalT)
        val singleFps = 1000.0 / singleMs

        log("")
        log("SINGLE-THREADED  ($WARMUP_FRAMES warm-up frames discarded, $n timed)")
        log("  cell pixel reads  %7.2f ms/frame".format(meanMs(readT) + meanMs(gatherT)))
        log("    bulk getPixels  %7.2f".format(meanMs(readT)))
        log("    cell gather     %7.2f".format(meanMs(gatherT)))
        log("  glyph classify    %7.2f ms/frame".format(meanMs(glyphT)))
        log("  colour classify   %7.2f ms/frame".format(meanMs(colourT)))
        log("  Reed-Solomon      EXCLUDED (not run)")
        log("  TOTAL             %7.2f ms/frame  (median %.2f)".format(singleMs, medianMs(totalT)))
        log("  → %.1f fps, %s at %d payload bytes/frame".format(singleFps, rate(singleFps, cfg), cfg.payloadBytesPerFrame))
        log("  throttle check: first 10 timed %.2f ms, last 10 timed %.2f ms → %s".format(
            meanMs(totalT, 0, 10), meanMs(totalT, n - 10, n), throttleVerdict(totalT)))
        log("  GCs during timed run: $gcSingle")
        log("  ground-truth check: OUTSIDE the timed region (between frames, %.3f ms/frame)".format(nsToMs(checkNs) / n))

        // ---------------------------------------------------------- thread pool
        val decoders = Array(cores) { FrameDecoder(codec) }
        // Each timed pool frame decodes into its own arrays, so checking can wait until timing ends.
        val poolGlyphs = Array(POOL_FRAMES) { ByteArray(codec.nCells) }
        val poolColours = Array(POOL_FRAMES) { ByteArray(codec.nCells) }
        val pool = Executors.newFixedThreadPool(cores)
        val poolT: LongArray
        val wallNs: Long
        val gcPool: Long
        try {
            runPool(pool, decoders, workingSet, truth, cfg, codec, WARMUP_FRAMES, null)
            poolT = LongArray(POOL_FRAMES)
            val gc0 = gcCount()
            val w0 = System.nanoTime()
            runPool(pool, decoders, workingSet, truth, cfg, codec, POOL_FRAMES, poolT, poolGlyphs, poolColours)
            wallNs = System.nanoTime() - w0
            gcPool = gcCount() - gc0
        } finally {
            pool.shutdown()
        }
        // Timed pool frames are checked here, after the clock has stopped.
        for (i in 0 until POOL_FRAMES) {
            val w = i % WORKING_SET
            if (decoder.countErrors(truth.glyphIds[w], truth.colourIds[w], poolGlyphs[i], poolColours[i]) != 0)
                stop(mismatchReport(decoder, cfg, codec, w, "thread-pool run, timed frame $i"))
        }
        val poolMs = nsToMs(wallNs) / POOL_FRAMES
        val poolFps = POOL_FRAMES / (wallNs / 1e9)

        log("")
        log("THREAD POOL  ($cores threads, $WARMUP_FRAMES warm-up frames discarded, $POOL_FRAMES timed, frames spread across threads)")
        log("  wall time         %7.2f ms/frame  (%.2f s for %d frames)".format(poolMs, wallNs / 1e9, POOL_FRAMES))
        log("  per-frame latency %7.2f ms mean on a worker".format(meanMs(poolT)))
        log("  → %.1f fps, %s  (%.2fx single-threaded)".format(poolFps, rate(poolFps, cfg), poolFps / singleFps))
        log("  throttle check: first 10 timed %.2f ms, last 10 timed %.2f ms → %s".format(
            meanMs(poolT, 0, 10), meanMs(poolT, POOL_FRAMES - 10, POOL_FRAMES), throttleVerdict(poolT)))
        log("  GCs during timed run: $gcPool")
        log("  ground-truth check: OUTSIDE the timed region (all $POOL_FRAMES frames checked after the clock stopped)")

        log("")
        log("SYMBOL ERROR RATE  0.0  (%d symbols checked: %d load-check + %d single-threaded + %d pool frames)".format(
            (nFrames + n + POOL_FRAMES).toLong() * codec.nCells * 2, nFrames, n, POOL_FRAMES))
        log("Thermal  ${thermalStatus()}")

        workingSet.forEach { it.recycle() }
        return Result(cfg.folder, singleMs, singleFps, singleFps * cfg.payloadBytesPerFrame,
            poolMs, poolFps, poolFps * cfg.payloadBytesPerFrame)
    }

    /**
     * Decodes [frames] frames across [pool], one worker per decoder, each pulling
     * the next frame index. Untimed runs ([times] null) check each frame on the worker.
     * Timed runs record per-frame decode time into [times] and decode into
     * [glyphOut]/[colourOut] without checking; the caller checks after timing.
     */
    private fun runPool(
        pool: ExecutorService,
        decoders: Array<FrameDecoder>,
        workingSet: List<Bitmap>,
        truth: GroundTruth,
        cfg: Configuration,
        codec: Codec,
        frames: Int,
        times: LongArray?,
        glyphOut: Array<ByteArray>? = null,
        colourOut: Array<ByteArray>? = null,
    ) {
        val next = AtomicInteger(0)
        val done = CountDownLatch(decoders.size)
        val bad = AtomicLong(-1)          // first bad frame index, or -1
        val failure = arrayOfNulls<String>(1)
        for (d in decoders) {
            pool.execute {
                try {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= frames || bad.get() >= 0) break
                        val w = i % WORKING_SET
                        if (times != null && glyphOut != null && colourOut != null) {
                            val t0 = System.nanoTime()
                            d.decode(workingSet[w], glyphOut[i], colourOut[i])
                            times[i] = System.nanoTime() - t0
                        } else {
                            d.decode(workingSet[w])
                            if (d.countErrors(truth.glyphIds[w], truth.colourIds[w]) != 0) {
                                synchronized(failure) {
                                    if (failure[0] == null) failure[0] = mismatchReport(d, cfg, codec, w, "pool warm-up")
                                }
                                bad.compareAndSet(-1, i.toLong())
                            }
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        failure[0]?.let { stop(it) }
    }

    private fun checkSymbols(d: FrameDecoder, truth: GroundTruth, k: Int, cfg: Configuration, codec: Codec, phase: String) {
        if (d.countErrors(truth.glyphIds[k], truth.colourIds[k]) != 0) stop(mismatchReport(d, cfg, codec, k, phase))
    }

    private fun mismatchReport(d: FrameDecoder, cfg: Configuration, codec: Codec, k: Int, phase: String): String {
        val i = d.firstErrorCell
        return "✗ BUG: symbol errors on clean frames — this is a decoder bug, not a result. No rate reported.\n" +
                "  ${cfg.folder}/${cfg.frames[k].name}, $phase\n" +
                "  ${d.glyphErrors} glyph errors, ${d.colourErrors} colour errors out of ${codec.nCells} cells\n" +
                "  first at cell $i (x ${codec.xs[i]}, y ${codec.ys[i]})"
    }

    // --------------------------------------------------------------- helpers

    private fun thermalStatus(): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (val s = pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "status $s"
        }
    }

    private fun gcCount(): Long = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1

    /** Payload rate in both bases, labelled: KB = 1000 bytes, KiB = 1024 bytes. */
    private fun rate(fps: Double, cfg: Configuration): String {
        val bps = fps * cfg.payloadBytesPerFrame
        return "%.0f KB/s (1 KB = 1000 B) = %.0f KiB/s".format(bps / 1000, bps / 1024)
    }

    private fun throttleVerdict(t: LongArray): String {
        val first = meanMs(t, 0, 10)
        val last = meanMs(t, t.size - 10, t.size)
        val change = (last / first - 1) * 100
        return if (change > THROTTLE_PERCENT) "THROTTLED (last ten %.0f%% slower)".format(change)
        else "no throttling (%+.0f%%, threshold +%d%%)".format(change, THROTTLE_PERCENT)
    }

    private fun nsToMs(ns: Long) = ns / 1e6
    private fun ms(ns: Long) = "%.0f".format(ns / 1e6)
    private fun meanMs(t: LongArray, from: Int = 0, to: Int = t.size): Double {
        var s = 0L
        for (i in from until to) s += t[i]
        return s / 1e6 / (to - from)
    }
    private fun medianMs(t: LongArray): Double {
        val sorted = t.sortedArray()
        val m = sorted.size / 2
        return (if (sorted.size % 2 == 1) sorted[m].toDouble() else (sorted[m - 1] + sorted[m]) / 2.0) / 1e6
    }

    companion object {
        const val TAG = "PRISMBENCH"
        const val WORKING_SET = 10
        const val WARMUP_FRAMES = 20
        const val SINGLE_FRAMES = 200
        const val POOL_FRAMES = 400
        const val THROTTLE_PERCENT = 10
        private val running = AtomicBoolean(false)
    }
}
