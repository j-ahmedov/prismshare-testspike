package io.github.ahmedov.prismshare.testspike.bench

import android.util.JsonReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Raised for anything wrong with the bundle itself. The message is shown as is. */
open class BundleException(message: String) : Exception(message)

/** A file the bundle should contain is not on the device: the push is missing or incomplete. */
class MissingBundleFileException(val file: File) : BundleException("missing file ${file.path}")

const val KNOWN_FRAME_FORMAT_VERSION = 2

class Manifest(
    val frameFormatVersion: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val configurations: List<Configuration>,
)

class Configuration(
    val dir: File,
    val folder: String,
    val payloadBytesPerFrame: Int,
    val params: File,
    val codec: File,
    val groundTruth: File,
    val frames: List<File>,
)

/**
 * codec.json, flattened into primitive arrays. Nothing here is derived from the
 * frames: every value is read from the file, and only checked for consistency.
 */
class Codec(
    val frameWidth: Int,
    val frameHeight: Int,
    val nCells: Int,
    val xs: IntArray,
    val ys: IntArray,
    val widths: IntArray,
    val heights: IntArray,
    val glyphCount: Int,
    val glyphWidth: Int,
    val glyphHeight: Int,
    /** Ink pixels per glyph; the same for every glyph. */
    val inkPerGlyph: Int,
    /** glyphInk[g * inkPerGlyph + k] = row * glyphWidth + column of the k-th ink pixel of glyph g. */
    val glyphInk: IntArray,
    val paletteR: IntArray,
    val paletteG: IntArray,
    val paletteB: IntArray,
    val backgroundR: Int,
    val backgroundG: Int,
    val backgroundB: Int,
)

/** ground_truth.json, in manifest frame order. */
class GroundTruth(
    val glyphIds: Array<ByteArray>,
    val colourIds: Array<ByteArray>,
    val sha256: Array<String>,
)

private fun fail(msg: String): Nothing = throw BundleException(msg)

private fun readJson(f: File): JSONObject {
    if (!f.isFile) throw MissingBundleFileException(f)
    return JSONObject(f.readText())
}

fun loadManifest(benchDir: File): Manifest {
    val m = readJson(File(benchDir, "manifest.json"))
    val version = m.getInt("frame_format_version")
    if (version != KNOWN_FRAME_FORMAT_VERSION)
        fail("manifest frame_format_version $version, this benchmark knows $KNOWN_FRAME_FORMAT_VERSION")
    val arr = m.getJSONArray("configurations")
    val configs = (0 until arr.length()).map { i ->
        val c = arr.getJSONObject(i)
        val folder = c.getString("folder")
        val dir = File(benchDir, folder)
        val names = c.getJSONArray("frames")
        val cfg = Configuration(
            dir = dir,
            folder = folder,
            payloadBytesPerFrame = c.getInt("payload_bytes_per_frame"),
            params = File(dir, c.getString("params")),
            codec = File(dir, c.getString("codec")),
            groundTruth = File(dir, c.getString("ground_truth")),
            frames = (0 until names.length()).map { File(dir, names.getString(it)) },
        )
        (listOf(cfg.params, cfg.codec, cfg.groundTruth) + cfg.frames).forEach {
            if (!it.isFile) throw MissingBundleFileException(it)
        }
        cfg
    }
    return Manifest(version, m.getInt("frame_width"), m.getInt("frame_height"), configs)
}

private fun rgb(a: JSONArray, what: String): IntArray {
    if (a.length() != 3) fail("$what is not an [R, G, B] triple")
    return IntArray(3) { a.getInt(it) }
}

fun loadCodec(f: File, manifest: Manifest): Codec {
    val c = readJson(f)
    val version = c.getInt("frame_format_version")
    if (version != manifest.frameFormatVersion)
        fail("${f.path}: frame_format_version $version, manifest says ${manifest.frameFormatVersion}")
    val fw = c.getInt("frame_width")
    val fh = c.getInt("frame_height")
    if (fw != manifest.frameWidth || fh != manifest.frameHeight)
        fail("${f.path}: frame ${fw}x$fh, manifest says ${manifest.frameWidth}x${manifest.frameHeight}")

    // Glyphs: glyphs[glyph_id][row][column], 1 = ink.
    val glyphs = c.getJSONArray("glyphs")
    val glyphCount = glyphs.length()
    if (glyphCount != c.getInt("glyph_count")) fail("${f.path}: glyphs has $glyphCount entries, glyph_count says ${c.getInt("glyph_count")}")
    // Ids are stored as bytes.
    if (glyphCount > 127) fail("${f.path}: $glyphCount glyphs, at most 127 supported")
    val gh = glyphs.getJSONArray(0).length()
    val gw = glyphs.getJSONArray(0).getJSONArray(0).length()
    val inkLists = (0 until glyphCount).map { g ->
        val rows = glyphs.getJSONArray(g)
        if (rows.length() != gh) fail("${f.path}: glyph $g has ${rows.length()} rows, glyph 0 has $gh")
        val ink = ArrayList<Int>()
        for (r in 0 until gh) {
            val row = rows.getJSONArray(r)
            if (row.length() != gw) fail("${f.path}: glyph $g row $r has ${row.length()} columns, expected $gw")
            for (col in 0 until gw) if (row.getInt(col) == 1) ink.add(r * gw + col)
        }
        ink
    }
    // glyph_ink_pixels is either one count for all glyphs or a per-glyph array.
    val declaredInk = c.get("glyph_ink_pixels")
    for (g in 0 until glyphCount) {
        val declared = if (declaredInk is JSONArray) declaredInk.getInt(g) else c.getInt("glyph_ink_pixels")
        if (inkLists[g].size != declared)
            fail("${f.path}: glyph $g bitmap has ${inkLists[g].size} ink pixels, glyph_ink_pixels says $declared")
    }
    val inkPerGlyph = inkLists[0].size
    if (inkLists.any { it.size != inkPerGlyph })
        fail("${f.path}: glyphs differ in ink-pixel count; the nearest-match classifier here assumes equal counts")
    val glyphInk = IntArray(glyphCount * inkPerGlyph)
    inkLists.forEachIndexed { g, ink -> ink.forEachIndexed { k, p -> glyphInk[g * inkPerGlyph + k] = p } }

    // Palette, in encoder index order.
    val palette = c.getJSONArray("palette")
    if (palette.length() != c.getInt("colour_depth"))
        fail("${f.path}: palette has ${palette.length()} entries, colour_depth says ${c.getInt("colour_depth")}")
    if (palette.length() > 127) fail("${f.path}: ${palette.length()} colours, at most 127 supported")
    val pal = (0 until palette.length()).map { rgb(palette.getJSONArray(it), "palette[$it]") }
    val bg = rgb(c.getJSONArray("background"), "background")

    // Cells, flattened.
    val cells = c.getJSONArray("cells")
    val n = cells.length()
    if (n != c.getInt("n_cells")) fail("${f.path}: cells has $n entries, n_cells says ${c.getInt("n_cells")}")
    val xs = IntArray(n); val ys = IntArray(n); val ws = IntArray(n); val hs = IntArray(n)
    for (i in 0 until n) {
        val cell = cells.getJSONObject(i)
        xs[i] = cell.getInt("x"); ys[i] = cell.getInt("y")
        ws[i] = cell.getInt("width"); hs[i] = cell.getInt("height")
        if (ws[i] != gw || hs[i] != gh)
            fail("${f.path}: cell $i is ${ws[i]}x${hs[i]} but glyph bitmaps are ${gw}x$gh")
        if (xs[i] < 0 || ys[i] < 0 || xs[i] + ws[i] > fw || ys[i] + hs[i] > fh)
            fail("${f.path}: cell $i lies outside the ${fw}x$fh frame")
    }

    return Codec(
        frameWidth = fw, frameHeight = fh, nCells = n,
        xs = xs, ys = ys, widths = ws, heights = hs,
        glyphCount = glyphCount, glyphWidth = gw, glyphHeight = gh,
        inkPerGlyph = inkPerGlyph, glyphInk = glyphInk,
        paletteR = IntArray(pal.size) { pal[it][0] },
        paletteG = IntArray(pal.size) { pal[it][1] },
        paletteB = IntArray(pal.size) { pal[it][2] },
        backgroundR = bg[0], backgroundG = bg[1], backgroundB = bg[2],
    )
}

/** Streams ground_truth.json (8 MB, 3.7 M numbers) straight into byte arrays. */
fun loadGroundTruth(cfg: Configuration, codec: Codec): GroundTruth {
    val byName = HashMap<String, Int>()
    cfg.frames.forEachIndexed { i, file -> byName[file.name] = i }
    val glyphs = arrayOfNulls<ByteArray>(cfg.frames.size)
    val colours = arrayOfNulls<ByteArray>(cfg.frames.size)
    val shas = arrayOfNulls<String>(cfg.frames.size)

    fun JsonReader.readIds(what: String, file: String): ByteArray {
        val out = ByteArray(codec.nCells)
        var i = 0
        beginArray()
        while (hasNext()) {
            val v = nextInt()
            if (i == out.size) fail("${cfg.groundTruth.path}: $file $what has more than ${codec.nCells} entries")
            if (v < 0 || v > 255) fail("${cfg.groundTruth.path}: $file $what[$i] = $v out of range")
            out[i++] = v.toByte()
        }
        endArray()
        if (i != out.size) fail("${cfg.groundTruth.path}: $file $what has $i entries, expected ${codec.nCells}")
        return out
    }

    JsonReader(cfg.groundTruth.bufferedReader()).use { r ->
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "frames") { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                var file: String? = null
                var sha: String? = null
                var g: ByteArray? = null
                var col: ByteArray? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "file" -> file = r.nextString()
                        "png_sha256" -> sha = r.nextString()
                        "glyph_ids" -> g = r.readIds("glyph_ids", file ?: "?")
                        "colour_ids" -> col = r.readIds("colour_ids", file ?: "?")
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (file == null || sha == null || g == null || col == null)
                    fail("${cfg.groundTruth.path}: a frame entry lacks file, png_sha256, glyph_ids or colour_ids")
                val k = byName[file] ?: fail("${cfg.groundTruth.path}: $file is not in the manifest")
                glyphs[k] = g; colours[k] = col; shas[k] = sha.lowercase()
            }
            r.endArray()
        }
        r.endObject()
    }
    cfg.frames.forEachIndexed { i, file ->
        if (glyphs[i] == null) fail("${cfg.groundTruth.path}: no entry for ${file.name}")
    }
    return GroundTruth(
        Array(glyphs.size) { glyphs[it]!! },
        Array(colours.size) { colours[it]!! },
        Array(shas.size) { shas[it]!! },
    )
}
