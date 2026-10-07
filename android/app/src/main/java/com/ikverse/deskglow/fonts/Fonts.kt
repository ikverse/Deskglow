package com.ikverse.deskglow.fonts

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.res.ResourcesCompat
import com.ikverse.deskglow.R
import java.io.File

/**
 * How a font is named in a widget's settings:
 * - "thin" and "bold": the phone's own font (Roboto on most phones);
 * - "b:<name>": a font that ships inside the app;
 * - "g:<Family>@<weight>": a font picked from Google Fonts and downloaded.
 */
object FontIds {
    const val THIN = "thin"
    const val BOLD = "bold"

    fun google(family: String, weight: Int) = "g:$family@$weight"

    fun parseGoogle(id: String): Pair<String, Int>? {
        if (!id.startsWith("g:")) return null
        val family = id.substring(2).substringBeforeLast('@')
        val weight = id.substringAfterLast('@').toIntOrNull() ?: return null
        return if (family.isBlank()) null else family to weight
    }

    fun isFont(id: String) = id == THIN || id == BOLD || id.startsWith("b:") || id.startsWith("g:")
}

/** A font that ships inside the app, so it works with no internet. All are SIL Open Font License; see About. */
data class BundledFont(val id: String, val label: String, val resId: Int, val arabic: Boolean, val licenceFile: String)

object BundledFonts {
    val all = listOf(
        BundledFont("b:bebas_neue", "Bebas Neue", R.font.bebas_neue, false, "BebasNeue.txt"),
        BundledFont("b:cinzel", "Cinzel", R.font.cinzel, false, "Cinzel.txt"),
        BundledFont("b:share_tech_mono", "Share Tech Mono", R.font.share_tech_mono, false, "ShareTechMono.txt"),
        BundledFont("b:bungee", "Bungee", R.font.bungee, false, "Bungee.txt"),
        BundledFont("b:dancing_script", "Dancing Script", R.font.dancing_script, false, "DancingScript.txt"),
        BundledFont("b:outfit", "Outfit", R.font.outfit, false, "Outfit.txt"),
        BundledFont("b:orbitron", "Orbitron", R.font.orbitron, false, "Orbitron.txt"),
        BundledFont("b:michroma", "Michroma", R.font.michroma, false, "Michroma.txt"),
        BundledFont("b:audiowide", "Audiowide", R.font.audiowide, false, "Audiowide.txt"),
        BundledFont("b:righteous", "Righteous", R.font.righteous, false, "Righteous.txt"),
        BundledFont("b:monoton", "Monoton", R.font.monoton, false, "Monoton.txt"),
        // The four Arabic fonts also carry Latin letters and digits, so they are offered in both modes.
        BundledFont("b:cairo", "Cairo", R.font.cairo, true, "Cairo.txt"),
        BundledFont("b:tajawal", "Tajawal", R.font.tajawal, true, "Tajawal.txt"),
        BundledFont("b:reem_kufi", "Reem Kufi", R.font.reem_kufi, true, "ReemKufi.txt"),
        BundledFont("b:amiri", "Amiri", R.font.amiri, true, "Amiri.txt"),
    )

    fun find(id: String): BundledFont? = all.firstOrNull { it.id == id }
}

/** Turns a font id into something Android can draw with. Typefaces are loaded once and kept. */
class FontResolver(private val context: Context, private val library: FontLibrary?) {
    private val cache = HashMap<String, Typeface>()

    fun typeface(id: String): Typeface? {
        cache[id]?.let { return it }
        val loaded = when {
            id == FontIds.THIN -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            id == FontIds.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
            id.startsWith("b:") -> BundledFonts.find(id)?.let { runCatching { ResourcesCompat.getFont(context, it.resId) }.getOrNull() }
            id.startsWith("g:") -> library?.fileFor(id)?.takeIf(File::exists)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            else -> null
        }
        if (loaded != null) cache[id] = loaded
        return loaded
    }

    /** A preview font file (only a few characters), loaded without being kept. */
    fun preview(file: File): Typeface? = runCatching { Typeface.createFromFile(file) }.getOrNull()

    /** A short name for the strip: the font's own name, or "Thin"/"Bold" for the phone's font. */
    fun label(id: String): String = when {
        id == FontIds.THIN -> "Thin"
        id == FontIds.BOLD -> "Bold"
        id.startsWith("b:") -> BundledFonts.find(id)?.label ?: id
        id.startsWith("g:") -> FontIds.parseGoogle(id)?.first ?: id
        else -> id
    }
}

val LocalFonts = staticCompositionLocalOf<FontResolver> { error("No fonts provided") }
