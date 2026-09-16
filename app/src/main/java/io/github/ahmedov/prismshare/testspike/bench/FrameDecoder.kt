package io.github.ahmedov.prismshare.testspike.bench

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Classifies every cell of one frame. Holds its own scratch buffers, so one
 * instance per thread; nothing is allocated after construction.
 *
 * The four stages are separate passes so they can be timed separately:
 * [readPixels], [gatherCells], [classifyGlyphs], [classifyColours].
 */
class FrameDecoder(private val c: Codec) {

    private val area = c.glyphWidth * c.glyphHeight
    private val frame = IntArray(c.frameWidth * c.frameHeight)
    private val cellPx = IntArray(c.nCells * area)
    private val ink = IntArray(area)
    val glyphIds = ByteArray(c.nCells)
    val colourIds = ByteArray(c.nCells)

    var glyphErrors = 0; private set
    var colourErrors = 0; private set
    var firstErrorCell = -1; private set

    init {
        // Keeps the integer colour distances below Int.MAX_VALUE.
        require(c.inkPerGlyph <= 64) { "glyphs with more than 64 ink pixels are not supported" }
    }

    /** One bulk copy of the frame's pixels, as a camera buffer would arrive. */
    fun readPixels(bitmap: Bitmap) =
        bitmap.getPixels(frame, 0, c.frameWidth, 0, 0, c.frameWidth, c.frameHeight)

    /** Copies each cell's pixels, at the codec.json coordinates, into cellPx in cell order. */
    fun gatherCells() {
        val frame = frame; val out = cellPx; val fw = c.frameWidth
        val xs = c.xs; val ys = c.ys; val ws = c.widths; val hs = c.heights
        var o = 0
        for (i in 0 until c.nCells) {
            val x = xs[i]; val w = ws[i]
            var row = ys[i] * fw
            for (r in 0 until hs[i]) {
                System.arraycopy(frame, row + x, out, o, w)
                o += w
                row += fw
            }
        }
    }

    /**
     * Nearest glyph bitmap. Each pixel's ink level is its L1 distance from the
     * background colour. Every glyph has the same ink-pixel count, so the glyph
     * whose ink pixels carry the most ink level is the least-squares nearest
     * match to the cell at any ink contrast.
     */
    fun classifyGlyphs(out: ByteArray = glyphIds) {
        val px = cellPx; val inkLevel = ink
        val glyphInk = c.glyphInk; val nInk = c.inkPerGlyph; val nGlyphs = c.glyphCount
        val bgR = c.backgroundR; val bgG = c.backgroundG; val bgB = c.backgroundB
        val area = area
        var base = 0
        for (i in 0 until c.nCells) {
            for (p in 0 until area) {
                val v = px[base + p]
                inkLevel[p] = abs(((v shr 16) and 0xFF) - bgR) +
                        abs(((v shr 8) and 0xFF) - bgG) +
                        abs((v and 0xFF) - bgB)
            }
            var best = 0
            var bestScore = -1
            var o = 0
            for (g in 0 until nGlyphs) {
                var s = 0
                for (k in 0 until nInk) s += inkLevel[glyphInk[o + k]]
                if (s > bestScore) { bestScore = s; best = g }
                o += nInk
            }
            out[i] = best.toByte()
            base += area
        }
    }

    /** Nearest palette colour to the mean RGB over the classified glyph's ink pixels. */
    fun classifyColours(glyphs: ByteArray = glyphIds, out: ByteArray = colourIds) {
        val px = cellPx
        val glyphInk = c.glyphInk; val nInk = c.inkPerGlyph
        val palR = c.paletteR; val palG = c.paletteG; val palB = c.paletteB; val nColours = palR.size
        val area = area
        var base = 0
        for (i in 0 until c.nCells) {
            val o = glyphs[i] * nInk
            var sr = 0; var sg = 0; var sb = 0
            for (k in 0 until nInk) {
                val v = px[base + glyphInk[o + k]]
                sr += (v shr 16) and 0xFF
                sg += (v shr 8) and 0xFF
                sb += v and 0xFF
            }
            // Compare sums against nInk * palette rather than dividing.
            var best = 0
            var bestD = Int.MAX_VALUE
            for (col in 0 until nColours) {
                val dr = sr - nInk * palR[col]
                val dg = sg - nInk * palG[col]
                val db = sb - nInk * palB[col]
                val d = dr * dr + dg * dg + db * db
                if (d < bestD) { bestD = d; best = col }
            }
            out[i] = best.toByte()
            base += area
        }
    }

    /** Decodes into [glyphOut] and [colourOut]; by default this decoder's own arrays. */
    fun decode(bitmap: Bitmap, glyphOut: ByteArray = glyphIds, colourOut: ByteArray = colourIds) {
        readPixels(bitmap)
        gatherCells()
        classifyGlyphs(glyphOut)
        classifyColours(glyphOut, colourOut)
    }

    /** Compares decoded ids (by default the last decode's) with ground truth. Returns the number of wrong symbols. */
    fun countErrors(
        truthGlyphs: ByteArray,
        truthColours: ByteArray,
        glyphIds: ByteArray = this.glyphIds,
        colourIds: ByteArray = this.colourIds,
    ): Int {
        var ge = 0; var ce = 0; var first = -1
        for (i in 0 until c.nCells) {
            val gBad = glyphIds[i] != truthGlyphs[i]
            val cBad = colourIds[i] != truthColours[i]
            if (gBad) ge++
            if (cBad) ce++
            if ((gBad || cBad) && first < 0) first = i
        }
        glyphErrors = ge; colourErrors = ce; firstErrorCell = first
        return ge + ce
    }
}
