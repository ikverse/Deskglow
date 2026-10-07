package com.ikverse.deskglow.fonts

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/** The five kinds of font Google Fonts sorts its library into, under the names the app shows. */
enum class FontCategory(val label: String) { Sans("Sans"), Serif("Serif"), Display("Display"), Handwriting("Handwriting"), Mono("Mono") }

/** One family in the Google Fonts library, with what Deskglow needs to list and fetch it. */
data class CatalogFont(
    val family: String,
    val category: FontCategory,
    /** Google's own ranking: 1 is the most used. */
    val popularity: Int,
    val latin: Boolean,
    val arabic: Boolean,
    /** The upright weights it comes in, e.g. 400 and 700. */
    val weights: List<Int>,
)

/**
 * Google Fonts, the open-source font library. Nothing here needs an account or a key:
 * - the list of families is the one fonts.google.com itself loads, and carries Google's popularity
 *   ranking (Google's documented list needs a key, so this one is used instead; if it ever changes,
 *   only "More fonts" is affected and the app says so);
 * - a font file is fetched through Google's CSS service, which can cut a font down to just the
 *   characters asked for: a clock preview is about 5 KB.
 */
object GoogleFonts {
    const val CATALOG_URL = "https://fonts.google.com/metadata/fonts"

    fun cssUrl(family: String, weight: Int, text: String? = null): String {
        val base = "https://fonts.googleapis.com/css2?family=${URLEncoder.encode(family, "UTF-8")}:wght@$weight"
        return if (text == null) base else "$base&text=${URLEncoder.encode(text, "UTF-8")}"
    }

    /** The TrueType file the CSS points at. Null if the answer holds no TrueType font. */
    fun fontUrl(css: String): String? =
        Regex("""url\((https://[^)]+)\)\s*format\('truetype'\)""").find(css)?.groupValues?.get(1)

    /** The available weight closest to [target], the heavier on a tie. */
    fun chooseWeight(weights: List<Int>, target: Int): Int =
        weights.minWithOrNull(compareBy<Int> { abs(it - target) }.thenByDescending { it }) ?: 400

    /**
     * Reads the library's list, keeping the open-source families that cover Latin or Arabic, most
     * popular first. Google's own families (Roboto, Noto, Google Sans) are flagged "brand" in the list
     * but are SIL Open Font License like the rest, so they stay. Anything ever marked not open source
     * is left out. Any guard text before the JSON (Google has sent ")]}'" there) is skipped.
     */
    fun parseCatalog(text: String): List<CatalogFont> {
        val json = JSONObject(text.substring(text.indexOf('{')))
        val families = json.getJSONArray("familyMetadataList")
        val out = ArrayList<CatalogFont>(families.length())
        for (index in 0 until families.length()) {
            val family = families.getJSONObject(index)
            if (!family.optBoolean("isOpenSource", true)) continue
            val subsets = family.optJSONArray("subsets").strings()
            val latin = "latin" in subsets
            val arabic = "arabic" in subsets
            if (!latin && !arabic) continue
            val weights = family.optJSONObject("fonts")?.keys()?.asSequence()
                ?.mapNotNull { it.toIntOrNull() }?.sorted()?.toList().orEmpty()
            if (weights.isEmpty()) continue
            out += CatalogFont(
                family = family.getString("family"),
                category = categoryOf(family.optString("category")),
                popularity = family.optInt("popularity", Int.MAX_VALUE),
                latin = latin,
                arabic = arabic,
                weights = weights,
            )
        }
        return out.sortedBy { it.popularity }
    }

    /** The cut-down form the app keeps on the phone between visits: only the fields above. */
    fun encodeCatalog(fonts: List<CatalogFont>): String {
        val array = JSONArray()
        for (font in fonts) {
            array.put(
                JSONObject().put("f", font.family).put("c", font.category.name).put("p", font.popularity)
                    .put("l", font.latin).put("a", font.arabic).put("w", JSONArray(font.weights)),
            )
        }
        return array.toString()
    }

    fun decodeCatalog(text: String): List<CatalogFont> {
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val font = array.getJSONObject(index)
            val weights = font.getJSONArray("w")
            CatalogFont(
                family = font.getString("f"),
                category = runCatching { FontCategory.valueOf(font.getString("c")) }.getOrDefault(FontCategory.Sans),
                popularity = font.getInt("p"),
                latin = font.getBoolean("l"),
                arabic = font.getBoolean("a"),
                weights = (0 until weights.length()).map(weights::getInt),
            )
        }
    }

    private fun categoryOf(name: String): FontCategory = when (name) {
        "Serif" -> FontCategory.Serif
        "Display" -> FontCategory.Display
        "Handwriting" -> FontCategory.Handwriting
        "Monospace" -> FontCategory.Mono
        else -> FontCategory.Sans
    }

    private fun JSONArray?.strings(): Set<String> =
        if (this == null) emptySet() else (0 until length()).map(::getString).toSet()
}
