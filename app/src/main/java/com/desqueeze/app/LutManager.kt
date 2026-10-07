package com.desqueeze.app

import android.content.Context
import android.net.Uri
import java.io.File

data class LutEntry(val id: String, val name: String, val size: Int)

/** Stores user-imported .cube LUTs in app-private storage. Nothing is bundled. */
class LutManager(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "luts").apply { mkdirs() }
    private val names = ctx.getSharedPreferences("lut_names", Context.MODE_PRIVATE)

    fun list(): List<LutEntry> = (dir.listFiles() ?: emptyArray()).filter { it.extension == "cube" }
        .map { LutEntry(it.nameWithoutExtension, names.getString(it.nameWithoutExtension, it.nameWithoutExtension)!!, 0) }
        .sortedBy { it.name.lowercase() }

    fun import(uri: Uri, displayName: String): LutEntry {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Could not read file")
        val cube = parse(text) // validates
        val id = newId()
        File(dir, "$id.cube").writeText(text)
        val nice = displayName.substringBeforeLast('.')
        names.edit().putString(id, nice).apply()
        return LutEntry(id, nice, cube.size)
    }

    /** Result of importing several files: LUTs added, names already in the library, and files that failed (name to reason). */
    data class ImportReport(val added: List<LutEntry>, val duplicates: List<String>, val failed: List<Pair<String, String>>) {
        val summary: String get() = lutImportSummary(added.size, duplicates, failed)
    }

    /**
     * Imports several .cube files (call off the main thread). Each is validated; files whose LUT data is already in
     * the library are skipped, so importing a folder twice doesn't create copies.
     */
    fun importAll(files: List<Pair<Uri, String>>): ImportReport {
        val known = HashSet<String>()
        (dir.listFiles() ?: emptyArray()).filter { it.extension == "cube" }.forEach { f -> runCatching { known += lutHash(f.readText()) } }
        val added = mutableListOf<LutEntry>(); val dup = mutableListOf<String>(); val failed = mutableListOf<Pair<String, String>>()
        for ((uri, display) in files) {
            val nice = display.substringBeforeLast('.')
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalArgumentException("couldn't read the file")
                val cube = parse(text)
                if (!known.add(lutHash(text))) { dup += nice; continue }
                val id = newId()
                File(dir, "$id.cube").writeText(text)
                names.edit().putString(id, nice).apply()
                added += LutEntry(id, nice, cube.size)
            } catch (e: Exception) { failed += nice to (e.message ?: e.javaClass.simpleName) }
        }
        return ImportReport(added, dup, failed)
    }

    /** A new id that no stored LUT uses (several files imported in the same millisecond used to collide). */
    private fun newId(): String {
        val t = System.currentTimeMillis()
        var n = 0
        while (true) {
            val id = if (n == 0) "lut_$t" else "lut_${t}_$n"
            if (!File(dir, "$id.cube").exists()) return id
            n++
        }
    }
    fun rename(id: String, newName: String) = names.edit().putString(id, newName.trim()).apply()
    fun delete(id: String) { File(dir, "$id.cube").delete(); names.edit().remove(id).apply() }
    fun load(id: String): CubeLut = parse(File(dir, "$id.cube").readText())

    class CubeLut(val size: Int, val data: FloatArray, val domainMin: FloatArray, val domainMax: FloatArray) {
        /** Applies the LUT on the CPU (trilinear) to ARGB pixels, blended by [strength]. For still-frame compare. */
        fun applyTo(px: IntArray, strength: Float): IntArray {
            val n = size; val nm = n - 1; val s = strength.coerceIn(0f, 1f); val out = IntArray(px.size)
            fun at(r: Int, g: Int, b: Int, c: Int): Float {
                val v = data[(r + g * n + b * n * n) * 3 + c]
                return (v - domainMin[c]) / (domainMax[c] - domainMin[c])
            }
            for (i in px.indices) {
                val p = px[i]
                val rf = (p shr 16 and 255) / 255f * nm; val gf = (p shr 8 and 255) / 255f * nm; val bf = (p and 255) / 255f * nm
                val r0 = rf.toInt().coerceAtMost(nm - 1); val g0 = gf.toInt().coerceAtMost(nm - 1); val b0 = bf.toInt().coerceAtMost(nm - 1)
                val dr = rf - r0; val dg = gf - g0; val db = bf - b0
                var rgb = 0
                for (c in 0..2) {
                    val c00 = at(r0, g0, b0, c) * (1 - dr) + at(r0 + 1, g0, b0, c) * dr
                    val c10 = at(r0, g0 + 1, b0, c) * (1 - dr) + at(r0 + 1, g0 + 1, b0, c) * dr
                    val c01 = at(r0, g0, b0 + 1, c) * (1 - dr) + at(r0 + 1, g0, b0 + 1, c) * dr
                    val c11 = at(r0, g0 + 1, b0 + 1, c) * (1 - dr) + at(r0 + 1, g0 + 1, b0 + 1, c) * dr
                    val lut = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
                    val orig = (p shr (16 - 8 * c) and 255) / 255f
                    val v = ((orig + (lut - orig) * s).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                    rgb = rgb or (v shl (16 - 8 * c))
                }
                out[i] = (0xFF shl 24) or rgb
            }
            return out
        }

        /**
         * Packs to Media3's int[r][g][b] ARGB cube, blended with identity by [strength].
         * Blending the table with identity == blending output with input (trilinear is linear).
         */
        fun toArgbCube(strength: Float): Array<Array<IntArray>> {
            val n = size; val s = strength.coerceIn(0f, 1f)
            return Array(n) { r -> Array(n) { g -> IntArray(n) { b ->
                val i = (r + g * n + b * n * n) * 3 // .cube: red varies fastest
                fun ch(c: Int, idx: Int): Int {
                    val ident = idx / (n - 1f)
                    val v = (data[i + c] - domainMin[c]) / (domainMax[c] - domainMin[c])
                    return ((ident + (v - ident) * s).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                }
                (0xFF shl 24) or (ch(0, r) shl 16) or (ch(1, g) shl 8) or ch(2, b)
            } } }
        }
    }

    companion object {
        fun parse(text: String): CubeLut {
            var size = 0; val min = floatArrayOf(0f, 0f, 0f); val max = floatArrayOf(1f, 1f, 1f)
            val vals = ArrayList<Float>()
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { l ->
                val p = l.split(Regex("\\s+"))
                when {
                    p[0] == "LUT_3D_SIZE" -> size = p[1].toInt()
                    p[0] == "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUTs are not supported; use a 3D .cube LUT.")
                    p[0] == "DOMAIN_MIN" -> for (k in 0..2) min[k] = p[k + 1].toFloat()
                    p[0] == "DOMAIN_MAX" -> for (k in 0..2) max[k] = p[k + 1].toFloat()
                    p[0].first().isDigit() || p[0].first() == '-' || p[0].first() == '.' ->
                        if (p.size >= 3) { vals += p[0].toFloat(); vals += p[1].toFloat(); vals += p[2].toFloat() }
                }
            }
            require(size in 2..65) { "Missing or invalid LUT_3D_SIZE." }
            require(vals.size == size * size * size * 3) { "LUT has ${vals.size / 3} entries, expected ${size * size * size}." }
            return CubeLut(size, vals.toFloatArray(), min, max)
        }
    }
}

/** Fingerprint of a LUT's data, ignoring comments, the title, blank lines and spacing, to spot duplicates. */
fun lutHash(text: String): String {
    val ws = Regex("\\s+")
    val norm = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("TITLE") }
        .joinToString("\n") { it.split(ws).joinToString(" ") }
    return java.security.MessageDigest.getInstance("SHA-256").digest(norm.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** One-line summary of a batch import, e.g. "Imported 3 LUTs. Skipped 1 already in your library." */
fun lutImportSummary(added: Int, duplicates: List<String>, failed: List<Pair<String, String>>): String {
    val parts = mutableListOf<String>()
    if (added > 0) parts += "Imported $added LUT${if (added == 1) "" else "s"}."
    if (duplicates.isNotEmpty()) parts += if (duplicates.size == 1) "Skipped “${duplicates[0]}”: already in your library."
        else "Skipped ${duplicates.size} already in your library."
    if (failed.isNotEmpty()) {
        val shown = failed.take(3).joinToString("; ") { (n, why) -> "“$n” ($why)" }
        parts += "Couldn't import ${failed.size}: $shown" + (if (failed.size > 3) "; and ${failed.size - 3} more." else ".")
    }
    return if (parts.isEmpty()) "No LUTs imported." else parts.joinToString(" ")
}
