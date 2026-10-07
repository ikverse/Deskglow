package com.ikverse.deskglow.fonts

import android.content.Context
import com.ikverse.deskglow.data.Http
import com.ikverse.deskglow.store.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A library font the owner picked: downloaded once and kept, so the screen saver never needs the internet. */
data class PickedFont(val family: String, val weight: Int, val latin: Boolean, val arabic: Boolean) {
    val id: String get() = FontIds.google(family, weight)
}

/**
 * The bridge to Google Fonts. It only ever goes online from the editor's "More fonts" sheet: the
 * family list (refreshed at most weekly), previews cut down to the characters they show, and the
 * full file of a font that is picked.
 */
class FontLibrary(
    context: Context,
    private val prefs: AppPrefs,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val fontsDir = File(context.filesDir, "fonts")
    private val previewDir = File(context.cacheDir, "font-previews")
    private val catalogFile = File(context.filesDir, "font-catalog.json")
    private var catalogInMemory: List<CatalogFont>? = null
    /** At most four previews download at once, however fast the grid is scrolled. */
    private val previewGate = Semaphore(4)

    private val pickedState = MutableStateFlow(readPicked())
    val picked: StateFlow<List<PickedFont>> = pickedState.asStateFlow()

    /** The library's families, most popular first. Uses the copy on the phone if it is under a week old. */
    suspend fun catalog(): List<CatalogFont> = withContext(Dispatchers.IO) {
        catalogInMemory?.let { if (fresh()) return@withContext it }
        if (fresh()) runCatching { GoogleFonts.decodeCatalog(catalogFile.readText()) }.getOrNull()?.let {
            catalogInMemory = it
            return@withContext it
        }
        try {
            val fonts = GoogleFonts.parseCatalog(String(http.get(GoogleFonts.CATALOG_URL)))
            if (fonts.isEmpty()) throw IOException("The font list came back empty")
            catalogFile.writeText(GoogleFonts.encodeCatalog(fonts))
            catalogInMemory = fonts
            fonts
        } catch (e: Exception) {
            // Offline or changed: an older copy is better than nothing.
            runCatching { GoogleFonts.decodeCatalog(catalogFile.readText()) }.getOrNull()?.also { catalogInMemory = it }
                ?: throw IOException("Google Fonts could not be reached", e)
        }
    }

    private fun fresh() = catalogFile.exists() && clock() - catalogFile.lastModified() < WEEK_MS

    /** A font file holding only the characters in [text], for a preview. Kept in the cache, which Android may clear. */
    suspend fun preview(family: String, weight: Int, text: String): File = withContext(Dispatchers.IO) {
        previewDir.mkdirs()
        val file = File(previewDir, hash("$family|$weight|$text") + ".ttf")
        if (!file.exists()) previewGate.withPermit { if (!file.exists()) download(GoogleFonts.cssUrl(family, weight, text), file) }
        file
    }

    /** Downloads the whole of [font] at [weight] and remembers it as picked. */
    suspend fun pick(font: CatalogFont, weight: Int): PickedFont = withContext(Dispatchers.IO) {
        val picked = PickedFont(font.family, weight, font.latin, font.arabic)
        val file = fileFor(picked.id) ?: throw IOException("Bad font id")
        if (!file.exists()) download(GoogleFonts.cssUrl(font.family, weight), file)
        if (pickedState.value.none { it.id == picked.id }) {
            pickedState.value = pickedState.value + picked
            prefs.pickedFonts = encodePicked(pickedState.value)
        }
        picked
    }

    /** Where a picked font's file lives; null for ids that are not library fonts. */
    fun fileFor(id: String): File? {
        val (family, weight) = FontIds.parseGoogle(id) ?: return null
        return File(fontsDir, family.replace(Regex("[^A-Za-z0-9]"), "_") + "-$weight.ttf")
    }

    private fun download(cssUrl: String, target: File) {
        val css = String(http.get(cssUrl))
        val url = GoogleFonts.fontUrl(css) ?: throw IOException("No TrueType font in the answer")
        val bytes = http.get(url)
        // A TrueType file starts with 00 01 00 00 (or "true"); anything else is not a font.
        if (bytes.size < 4 || !(bytes[0] == 0.toByte() && bytes[1] == 1.toByte() || String(bytes, 0, 4) == "true")) {
            throw IOException("The download is not a TrueType font")
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) throw IOException("Could not save the font")
    }

    private fun readPicked(): List<PickedFont> = prefs.pickedFonts?.let { text ->
        runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { index ->
                val font = array.getJSONObject(index)
                PickedFont(font.getString("family"), font.getInt("weight"), font.optBoolean("latin", true), font.optBoolean("arabic", false))
            }
        }.getOrNull()
    }.orEmpty()

    private fun encodePicked(fonts: List<PickedFont>): String = JSONArray().also { array ->
        fonts.forEach { array.put(JSONObject().put("family", it.family).put("weight", it.weight).put("latin", it.latin).put("arabic", it.arabic)) }
    }.toString()

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
