@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ExYuTeka : MainAPI() {
    override var mainUrl = "https://exyuteka.net"
    override var name = "ExYuTeka"
    override val hasMainPage = true
    override var lang = "un"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    private val byseUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private val embedOrigin = "exyuteka.net"

    private fun dbg(msg: String) {
        Log.d("ExYuTeka", msg)
    }

    private class AttestCache(
        val token: String, val viewerId: String, val deviceId: String,
        val confidence: Double, val ts: Long
    )

    companion object {
        private val attestCache = mutableMapOf<String, AttestCache>()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/#trending-filmovi" to "Trending Filmovi",
        "$mainUrl/#trending-serije" to "Trending Serije",
        "$mainUrl/nove-epizode.php" to "Nove Epizode",
        "$mainUrl/filmovi.php" to "Domaći Filmovi",
        "$mainUrl/serije.php" to "Domaće Serije",
        "$mainUrl/sinhronizovani.php" to "Sinhronizovani Filmovi",
        "$mainUrl/crtaci.php" to "Crtaći",
        "$mainUrl/rijaliti.php" to "Rijaliti TV",
        "$mainUrl/dokumentarne.php" to "Dokumentarne Emisije",
        "$mainUrl/latino.php" to "Latino Serije",
        "$mainUrl/korejske.php" to "Korejske Serije"
    )

    // ------------------------------------------------------------------ Karten

    private fun parseCardElement(element: Element): SearchResponse? {
        val targetLink = if (element.tagName() == "a") element
        else element.selectFirst("a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/'], a[href*='/ep/'], a")
            ?: return null

        val href = fixUrlNull(targetLink.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.endsWith(".php") || href.endsWith("#")) return null

        val img = element.selectFirst("img") ?: targetLink.selectFirst("img")
            ?: element.parent()?.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { null }
                ?: img?.attr("data-lazy-src")?.ifEmpty { null }
                ?: img?.attr("data-original")?.ifEmpty { null }
                ?: img?.attr("src")?.ifEmpty { null }
        )
        if (posterUrl != null && posterUrl.contains("logo") && !posterUrl.contains("slika")) return null

        val seriesName = element.selectFirst(".ep-series")?.text()?.trim()
        val epCode = element.selectFirst(".ep-code")?.text()?.trim()

        val title = when {
            !seriesName.isNullOrEmpty() && !epCode.isNullOrEmpty() -> "$seriesName ($epCode)"
            !seriesName.isNullOrEmpty() -> seriesName
            else -> element.selectFirst(".card-title, .ep-title, .title, .name, h1, h2, h3, h4")?.text()?.trim()
                ?: targetLink.selectFirst(".card-title, .ep-title, .title, .name")?.text()?.trim()
                ?: img?.attr("alt")?.trim()?.ifEmpty { null }
                ?: targetLink.attr("title").trim().ifEmpty { null }
                ?: targetLink.text().trim().ifEmpty { null }
                ?: return null
        }

        if (title.lowercase() in listOf("početna", "filmovi", "serije", "najnovije", "nove epizode")) return null

        val isSeries = href.contains("/serija/") || href.contains("/gledaj-online/") ||
                href.contains("/ep/") || element.selectFirst(".serija-badge") != null ||
                element.hasClass("ep-row")

        return newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        document.select(
            "a.card, div.card, a.ep-row, div.ep-row, a.episode-item, .card-grid > a, .card-grid > div, .ep-list > a, " +
                    "a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/'], a[href*='/ep/']"
        ).forEach { elem ->
            val item = parseCardElement(elem) ?: return@forEach
            if (seen.add(item.url)) results.add(item)
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val reqData = request.data

        if (reqData.startsWith("$mainUrl/#trending-")) {
            val document = app.get(mainUrl, headers = headers).document
            val results = mutableListOf<SearchResponse>()
            val targetHeader = if (reqData.endsWith("serije")) "Trending Serije" else "Trending Filmovi"
            val section = document.select("section.section").firstOrNull { sec ->
                sec.selectFirst(".section-title")?.text()?.contains(targetHeader, true) == true
            }
            if (section != null) {
                section.select("a.card, div.card").forEach { el ->
                    parseCardElement(el)?.let { results.add(it) }
                }
            } else {
                results.addAll(parseCards(document))
            }
            return newHomePageResponse(request.name, results, hasNext = false)
        }

        val url = if (page <= 1) reqData else "$reqData?strana=$page"
        val document = app.get(url, headers = headers).document
        val items = parseCards(document)
        val hasNext = items.isNotEmpty() && document.select("a[href*='strana=${page + 1}']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        val categoryMap = mapOf(
            "serije" to "$mainUrl/serije.php", "serien" to "$mainUrl/serije.php",
            "series" to "$mainUrl/serije.php", "film" to "$mainUrl/filmovi.php",
            "filmovi" to "$mainUrl/filmovi.php", "movies" to "$mainUrl/filmovi.php",
            "crtaci" to "$mainUrl/crtaci.php", "sinhronizovani" to "$mainUrl/sinhronizovani.php",
            "dokumentarne" to "$mainUrl/dokumentarne.php", "latino" to "$mainUrl/latino.php",
            "korejske" to "$mainUrl/korejske.php", "rijaliti" to "$mainUrl/rijaliti.php",
            "nove epizode" to "$mainUrl/nove-epizode.php", "nove" to "$mainUrl/nove-epizode.php"
        )
        for ((keyword, url) in categoryMap) {
            if (q == keyword || q.contains(keyword)) {
                try {
                    val r = parseCards(app.get(url, headers = headers).document)
                    if (r.isNotEmpty()) return r
                } catch (_: Exception) {}
            }
        }
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        try {
            val r = parseCards(app.get("$mainUrl/pretraga.php?q=$encoded", headers = headers).document)
            if (r.isNotEmpty()) return r
        } catch (_: Exception) {}
        return emptyList()
    }

    // ------------------------------------------------------------------ Details

    override suspend fun load(url: String): LoadResponse {
        var targetUrl = url
        var document = app.get(targetUrl, headers = headers).document

        if (targetUrl.contains("/ep/")) {
            val parent = document.selectFirst("a[href*='/serija/'], a[href*='/gledaj-online/']")?.attr("href")
            if (!parent.isNullOrEmpty()) {
                targetUrl = fixUrl(parent)
                document = app.get(targetUrl, headers = headers).document
            }
        }

        val title = document.selectFirst(".single-title, h1, .title")?.text()?.trim() ?: "ExYuTeka"
        val posterImg = document.selectFirst(
            ".single-poster img, .poster img, img[src*='/slika'], .movie-poster img, .entry-poster img"
        )
        val posterUrl = fixUrlNull(
            posterImg?.attr("data-src")?.ifEmpty { null }
                ?: posterImg?.attr("data-lazy-src")?.ifEmpty { null }
                ?: posterImg?.attr("src")?.ifEmpty { null }
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.ifEmpty { null }
        )
        val plot = document.selectFirst(".single-desc, .description, p")?.text()?.trim()
        val year = document.selectFirst(".single-meta")?.text()?.let {
            Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }

        if (!targetUrl.contains("/film/")) {
            val episodes = mutableListOf<Episode>()
            val seasonBlocks = document.select(".season-block")

            if (seasonBlocks.isNotEmpty()) {
                seasonBlocks.forEachIndexed { sIndex, sBlock ->
                    val sTitle = sBlock.selectFirst(".season-title")?.text() ?: ""
                    val seasonNum = Regex("""\d+""").find(sTitle)?.value?.toIntOrNull() ?: (sIndex + 1)
                    sBlock.select("a.episode-item, a[href*='/ep/']").forEachIndexed { eIndex, epElem ->
                        val epHref = fixUrl(epElem.attr("href"))
                        val epNumText = epElem.selectFirst(".ep-num")?.text() ?: ""
                        val epTitleText = epElem.selectFirst(".ep-title")?.text() ?: epElem.text()
                        val seMatch = Regex("""[sS](\d+)[eE](\d+)""").find(epHref)
                        val episodeNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                            ?: Regex("""\d+""").find(epNumText)?.value?.toIntOrNull()
                            ?: Regex("""[eE]pizoda\s*(\d+)""").find(epTitleText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (eIndex + 1)
                        val epImg = epElem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-src")?.ifEmpty { null } ?: epImg?.attr("src")?.ifEmpty { null }
                        ) ?: posterUrl
                        episodes.add(newEpisode(epHref) {
                            this.name = "Epizoda $episodeNum"
                            this.season = seasonNum
                            this.episode = episodeNum
                            this.posterUrl = epPoster
                        })
                    }
                }
            } else {
                document.select("a.episode-item, a.ep-row, a[href*='/ep/']")
                    .filter { it.attr("href").isNotEmpty() }
                    .forEachIndexed { index, epElem ->
                        val epHref = fixUrl(epElem.attr("href"))
                        val epText = epElem.text().trim()
                        val seMatch = Regex("""[sS](\d+)[eE](\d+)""").find(epHref)
                            ?: Regex("""[sS](\d+)[eE](\d+)""").find(epText)
                        val sNum = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val eNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                            ?: Regex("""[eE]pizoda\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (index + 1)
                        val epImg = epElem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-src")?.ifEmpty { null } ?: epImg?.attr("src")?.ifEmpty { null }
                        ) ?: posterUrl
                        episodes.add(newEpisode(epHref) {
                            this.name = "Epizoda $eNum"
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = epPoster
                        })
                    }
            }

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
                    this.posterUrl = posterUrl
                    this.backgroundPosterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                }
            }
        }

        return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    // ------------------------------------------------------------------ Helfer

    private val junkWords = listOf(
        "googletag", "managertechnically", "al5sm", "aarems", "doubleclick", "googletagmanager",
        "google-analytics", "gstatic", "googleapis", "fonts.", "histats", "exoclick",
        "popads", "juicyads", "adsco", "favicon", "placeholder", "facebook.com", "twitter.com",
        "clickonserver", "challenge-platform", "cdn-cgi"
    )

    private fun isJunk(url: String): Boolean {
        val u = url.lowercase()
        if (junkWords.any { u.contains(it) }) return true
        val path = u.substringBefore("?")
        return listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".woff", ".woff2", ".ttf")
            .any { path.endsWith(it) }
    }

    private fun isOwnSite(url: String) = url.contains("exyuteka", true)

    private fun urlBase(u: String): String {
        val scheme = u.substringBefore("://", "https")
        val host = u.substringAfter("://").substringBefore("/")
        return "$scheme://$host"
    }

    private fun urlCode(u: String): String =
        u.substringAfter("://").substringAfter("/", "").substringBefore("?").substringBefore("#")
            .trimEnd('/').substringAfterLast("/")

    private fun unpack(html: String): String {
        val plain = html.replace("\\/", "/")
        return try {
            if (plain.contains("eval(function(p,a,c,k,e,")) getAndUnpack(plain).replace("\\/", "/") else plain
        } catch (_: Exception) { plain }
    }

    private fun extractServerUrls(html: String): List<String> {
        val out = linkedSetOf<String>()
        Regex("""case\s+\d+\s*:\s*src\s*=\s*["']([^"']+)["']""").findAll(html).forEach {
            out.add(it.groupValues[1].trim())
        }
        Regex("""\bsrc\s*=\s*["'](https?://[^"']+)["']""").findAll(html).forEach {
            out.add(it.groupValues[1].trim())
        }
        return out.filterNot { isJunk(it) }.toList()
    }

    private fun collectCandidates(doc: Document, rawHtml: String): Set<String> {
        val html = unpack(rawHtml)
        val out = linkedSetOf<String>()

        fun add(u: String?) {
            val v = u?.trim()?.trim('\'', '"') ?: return
            if (v.isEmpty() || v.startsWith("#") || v.startsWith("javascript") ||
                v.startsWith("blob:") || v.startsWith("data:")
            ) return
            if (v.startsWith("http") || v.startsWith("//") || v.startsWith("/")) out.add(fixUrl(v))
        }

        doc.select("iframe, frame, embed, source, video").forEach {
            add(it.attr("src")); add(it.attr("data-src"))
        }
        doc.select("a[href]").forEach { add(it.attr("href")) }
        Regex("""https?://[^\s"'<>\\)\]}]+""").findAll(html).forEach { add(it.value) }

        return out.filterNot { isJunk(it) }.toSet()
    }

    private suspend fun directVideos(
        doc: Document, rawHtml: String, pageUrl: String, label: String,
        emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = unpack(rawHtml)
        var found = false
        val cands = linkedSetOf<String>()

        doc.select("video source, video").forEach {
            val s = it.attr("src").ifEmpty { it.attr("data-src") }
            if (s.isNotEmpty()) cands.add(s)
        }
        listOf(
            Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']"""),
            Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']"""),
            Regex("""["'](/[^"'\s]+\.(?:mp4|m3u8)[^"'\s]*)["']"""),
            Regex("""(?:file|src|source|video_url|videoUrl|hls)\s*[:=]\s*["']([^"']+)["']""")
        ).forEach { r -> r.findAll(html).forEach { cands.add(it.groupValues[1]) } }

        for (raw in cands) {
            val u = fixUrl(raw.replace("\\/", "/").trim())
            if (u.isEmpty() || u.startsWith("blob:") || isJunk(u)) continue
            if (u.contains(".php") || u.contains("/watch") || u.contains("/embed/")) continue
            val clean = u.substringBefore("?")
            val isM3u8 = clean.endsWith(".m3u8") || u.contains(".m3u8")
            val isMp4 = clean.endsWith(".mp4") || u.contains("/video/") || u.contains("cdn.dsplayer")
            if (!isM3u8 && !isMp4) continue
            if (!emitted.add(u)) continue

            val ref = if (u.contains("dsplayer")) "https://dsplayer.site/" else pageUrl
            val q = when {
                u.contains("1080") -> Qualities.P1080.value
                u.contains("720") -> Qualities.P720.value
                u.contains("480") -> Qualities.P480.value
                else -> Qualities.P1080.value
            }
            dbg("DIRECT VIDEO: $u")
            callback(newExtractorLink(
                source = this.name, name = label, url = u,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = ref
                this.quality = q
            })
            found = true
        }
        return found
    }

    // ==================================================================
    //  BYSE / FILEMOON ENGINE
    // ==================================================================

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return Base64.decode(fixed + "=".repeat(pad), Base64.DEFAULT)
    }

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun big32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33)
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> raw
        }
    }

    private fun rl(v: Int, s: Int): Int = (v shl s) or (v ushr (32 - s))

    private fun quarter(st: IntArray) {
        st[0] += st[1]; st[3] = rl(st[3] xor st[0], 16)
        st[2] += st[3]; st[1] = rl(st[1] xor st[2], 12)
        st[0] += st[1]; st[3] = rl(st[3] xor st[0], 8)
        st[2] += st[3]; st[1] = rl(st[1] xor st[2], 7)
    }

    private fun powSingle(input: ByteArray, difficulty: Int): Boolean {
        val initConst = 2654435761L.toInt()
        val finalConst = 2246822519L.toInt()
        val st = IntArray(4)
        st[0] = 1779033703; st[1] = 3144134277L.toInt(); st[2] = 1013904242; st[3] = 2773480762L.toInt()

        for (b in input) {
            st[0] += b.toInt() and 0xFF
            st[0] = rl(st[0], 7)
            quarter(st)
        }
        repeat(8) { quarter(st) }

        val buf = IntArray(512)
        for (i in 0 until 512) {
            quarter(st)
            buf[i] = st[0] xor st[2]
        }
        repeat(2) {
            for (s in 0 until 512) {
                val a = buf[s] and 511
                var c = buf[s] + buf[a]
                c = rl(c, 13)
                c = c xor ((buf[(s + 1) and 511].toLong() * initConst).toInt())
                buf[s] = c
                st[0] = st[0] xor c
                quarter(st)
            }
        }

        quarter(st)
        var out = st[0]
        for (c in 0 until 64) {
            val d = buf[c]
            out += d
            out = rl(out, 5)
            out = out xor ((d.toLong() * finalConst).toInt())
        }
        out = out xor st[2]

        var leading = Integer.numberOfLeadingZeros(out)
        if (difficulty <= 32) return leading >= difficulty
        if (leading < 32) return false

        var total = leading
        var remaining = difficulty - 32
        for (i in 1 until 8) {
            if (remaining <= 0) break
            quarter(st)
            var v = st[0]
            val base = i * 64
            for (c in 0 until 64) {
                val d = buf[base + c]
                v += d
                v = rl(v, 5)
                v = v xor ((d.toLong() * finalConst).toInt())
            }
            v = v xor st[2]
            val lz = Integer.numberOfLeadingZeros(v)
            total += lz
            if (lz < 32) break
            remaining -= 32
        }
        return total >= difficulty
    }

    private fun powSolve(nonce: String, difficulty: Int, maxIter: Int = 500000): String? {
        val prefix = (nonce + ":").toByteArray(Charsets.ISO_8859_1)
        for (counter in 0 until maxIter) {
            val cb = counter.toString().toByteArray(Charsets.ISO_8859_1)
            val input = ByteArray(prefix.size + cb.size)
            System.arraycopy(prefix, 0, input, 0, prefix.size)
            System.arraycopy(cb, 0, input, prefix.size, cb.size)
            if (powSingle(input, difficulty)) return counter.toString()
        }
        return null
    }

    private fun toJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> v.toString()
        is Int -> v.toString()
        is Long -> v.toString()
        is Double -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "\"${it.key}\":${toJson(it.value)}" }
        is List<*> -> v.joinToString(",", "[", "]") { toJson(it) }
        else -> "\"" + v.toString() + "\""
    }

    private fun jStr(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    private suspend fun byseGet(url: String, host: String): String? =
        try {
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to byseUA,
                    "Accept" to "*/*",
                    "Referer" to "https://$host/",
                    "Origin" to "https://$host"
                )
            ).text
        } catch (e: Exception) {
            dbg("BYSE GET Fehler $url: ${e.message}")
            null
        }

    private suspend fun bysePost(url: String, payload: Map<String, Any?>, hdrs: Map<String, String>): String? =
        try {
            app.post(
                url,
                headers = hdrs + ("Content-Type" to "application/json"),
                requestBody = toJson(payload).toRequestBody("application/json".toMediaTypeOrNull())
            ).text
        } catch (e: Exception) {
            dbg("BYSE POST Fehler $url: ${e.message}")
            null
        }

    private fun clientFingerprint(): Map<String, Any?> {
        val widths = listOf(1920, 2560, 1366, 1440, 1680)
        val heights = listOf(1080, 1440, 768, 900, 1050)
        val i = (Math.random() * widths.size).toInt().coerceIn(0, widths.size - 1)
        fun randHash() = b64UrlEncode(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        return mapOf(
            "user_agent" to byseUA,
            "pixel_ratio" to listOf(1, 2).random(),
            "screen_width" to widths[i],
            "screen_height" to heights[i],
            "color_depth" to 24,
            "languages" to listOf("en-US", "en"),
            "timezone" to "Europe/Belgrade",
            "hardware_concurrency" to listOf(4, 8, 12, 16).random(),
            "touch_points" to 0,
            "webgl_vendor" to "Intel",
            "webgl_renderer" to "Intel(R) HD Graphics, or similar",
            "canvas_hash" to randHash(),
            "audio_hash" to randHash(),
            "webgl_params_hash" to randHash(),
            "fonts_hash" to randHash(),
            "codecs_hash" to randHash(),
            "media_devices" to "ai0ao0vi0",
            "pointer_type" to "fine,hover",
            "extra" to mapOf("vendor" to "", "appVersion" to "5.0 (Windows NT 10.0)")
        )
    }

    private fun legacyFingerprint(): Map<String, Any?> {
        val vId = (0 until 32).map { "0123456789abcdef".random() }.joinToString("")
        val dId = (0 until 32).map { "0123456789abcdef".random() }.joinToString("")
        val ctime = System.currentTimeMillis() / 1000
        val conf = Math.round((0.6 + Math.random() * 0.3) * 100) / 100.0
        val tData = linkedMapOf<String, Any?>(
            "viewer_id" to vId, "device_id" to dId, "confidence" to conf,
            "iat" to ctime, "exp" to ctime + 600
        )
        val bdata = b64UrlEncode(toJson(tData).toByteArray())
        val sig = b64UrlEncode(MessageDigest.getInstance("SHA-256").digest(bdata.toByteArray()))
        tData.remove("iat"); tData.remove("exp")
        tData["token"] = "$bdata.$sig"
        return mapOf("fingerprint" to tData)
    }

    private suspend fun challengePlayback(
        host: String, code: String, prefix: String, embedUrl: String
    ): String? {
        val apiHeaders = mapOf(
            "User-Agent" to byseUA,
            "Accept" to "*/*",
            "Referer" to "https://$embedOrigin/",
            "Origin" to "https://$host"
        )
        val embedExtra = mapOf(
            "X-Embed-Origin" to embedOrigin,
            "X-Embed-Referer" to "https://$embedOrigin/",
            "X-Embed-Parent" to embedUrl
        )

        val token: String
        val viewerId: String
        val deviceId: String
        val confidence: Double

        val cached = attestCache[host]?.takeIf { System.currentTimeMillis() - it.ts < 600_000 }
        if (cached != null) {
            token = cached.token; viewerId = cached.viewerId
            deviceId = cached.deviceId; confidence = cached.confidence
            dbg("BYSE attest cache für $host")
        } else {
            val challenge = bysePost("https://$host/api/videos/access/challenge", emptyMap(), apiHeaders)
                ?: return null
            val challengeId = jStr(challenge, "challenge_id") ?: return null
            val nonce = jStr(challenge, "nonce") ?: return null
            dbg("BYSE challenge ok")

            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(kp.private)
            signer.update(nonce.toByteArray())
            val signature = b64UrlEncode(signer.sign())
            val pub = kp.public as ECPublicKey
            val jwk = mapOf(
                "alg" to "ES256", "crv" to "P-256", "ext" to true,
                "key_ops" to listOf("verify"), "kty" to "EC",
                "x" to b64UrlEncode(big32(pub.w.affineX)),
                "y" to b64UrlEncode(big32(pub.w.affineY))
            )

            val attest = bysePost(
                "https://$host/api/videos/access/attest",
                mapOf(
                    "viewer_id" to "", "device_id" to "",
                    "challenge_id" to challengeId, "nonce" to nonce,
                    "signature" to signature, "public_key" to jwk,
                    "client" to clientFingerprint(),
                    "storage" to emptyMap<String, String>(),
                    "attributes" to mapOf("entropy" to "low")
                ),
                apiHeaders
            ) ?: return null
            val t = jStr(attest, "token") ?: return null
            val v = jStr(attest, "viewer_id") ?: ""
            val d = jStr(attest, "device_id") ?: ""
            val c = Regex(""""confidence"\s*:\s*([0-9.]+)""").find(attest)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.8
            attestCache[host] = AttestCache(t, v, d, c, System.currentTimeMillis())
            token = t; viewerId = v; deviceId = d; confidence = c
            dbg("BYSE attest ok")
        }

        val cookie = "byse_viewer_id=$viewerId; byse_device_id=$deviceId"
        val fpPayload = mapOf(
            "fingerprint" to mapOf(
                "token" to token, "viewer_id" to viewerId,
                "device_id" to deviceId, "confidence" to confidence
            )
        )
        val flowHeaders = apiHeaders + embedExtra + ("Cookie" to cookie)

        val captcha = bysePost("https://$host/api/videos/$code/${prefix}captcha", fpPayload, flowHeaders)
            ?: return null
        val powNonce = jStr(captcha, "pow_nonce") ?: return null
        val powDifficulty = Regex(""""pow_difficulty"\s*:\s*(\d+)""").find(captcha)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val powToken = jStr(captcha, "pow_token") ?: return null
        dbg("BYSE captcha: difficulty=$powDifficulty")

        val t0 = System.currentTimeMillis()
        val solution = powSolve(powNonce, powDifficulty) ?: return null
        dbg("BYSE PoW gelöst in ${System.currentTimeMillis() - t0}ms")

        val verify = bysePost(
            "https://$host/api/videos/$code/${prefix}captcha/verify",
            mapOf(
                "pow_token" to powToken,
                "solution" to solution,
                "fingerprint" to fpPayload["fingerprint"]
            ),
            flowHeaders
        ) ?: return null
        val captchaToken = jStr(verify, "token")
        if (captchaToken == null) {
            dbg("BYSE verify fehlgeschlagen: ${verify.take(120)}")
            attestCache.remove(host)
            return null
        }
        dbg("BYSE verify ok")

        return bysePost(
            "https://$host/api/videos/$code/${prefix}playback",
            fpPayload,
            flowHeaders + ("X-Captcha-Token" to captchaToken)
        )
    }

    private suspend fun legacyPlayback(host: String, code: String, prefix: String): String? =
        bysePost(
            "https://$host/api/videos/$code/${prefix}playback",
            legacyFingerprint(),
            mapOf(
                "User-Agent" to byseUA,
                "Accept" to "*/*",
                "Referer" to "https://$host/",
                "Origin" to "https://$host"
            )
        )

    private suspend fun parseByse(
        embedUrl: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!embedUrl.startsWith("http")) return false
        val code = urlCode(embedUrl)
        if (code.length < 4) return false
        var host = urlBase(embedUrl).substringAfter("//")

        dbg("BYSE start: $embedUrl")
        try {
            var prefix = "embed/"
            var details = byseGet("https://$host/api/videos/$code/${prefix}details", host)
            if (details == null || details.contains("\"error\"")) {
                details = byseGet("https://$host/api/videos/$code/details", host)
                prefix = ""
            }
            if (details == null) return false

            val frameUrl = jStr(details, "embed_frame_url")
            if (!frameUrl.isNullOrEmpty()) {
                val fHost = urlBase(frameUrl).substringAfter("//")
                if (fHost.isNotEmpty()) host = fHost
            }
            dbg("BYSE details ok, host=$host")

            val settings = byseGet("https://$host/api/videos/$code/${prefix}settings", host)
            val captchaRequired = settings != null &&
                    Regex(""""captcha_required"\s*:\s*true""").containsMatchIn(settings)
            dbg("BYSE captcha_required=$captchaRequired")

            val playback = if (captchaRequired) {
                challengePlayback(host, code, prefix, embedUrl)
            } else {
                legacyPlayback(host, code, prefix)
                    ?: challengePlayback(host, code, prefix, embedUrl)
            }
            if (playback == null) {
                dbg("BYSE playback leer")
                return false
            }

            var sourcesJson = playback
            if (playback.contains("\"playback\"")) {
                val pb = Regex(""""playback"\s*:\s*\{.*""", RegexOption.DOT_MATCHES_ALL)
                    .find(playback)?.value ?: playback
                val ivStr = jStr(pb, "iv") ?: return false
                val payloadStr = jStr(pb, "payload") ?: return false
                val partsBlock = Regex(""""key_parts"\s*:\s*\[([^\]]*)\]""").find(pb)?.groupValues?.get(1)
                    ?: return false
                var parts = Regex(""""([^"]+)"""").findAll(partsBlock).map { it.groupValues[1] }.toList()
                val version = Regex(""""version"\s*:\s*(\d+)""").find(pb)?.groupValues?.get(1)?.toIntOrNull()
                if (version != null && parts.size >= 2) {
                    val v = version
                    parts = listOf(parts[v - 1], parts[parts.size - v])
                }
                if (parts.size < 2) return false

                val keyBytes = b64UrlDecode(parts[0]) + b64UrlDecode(parts[1])
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(128, b64UrlDecode(ivStr))
                )
                sourcesJson = cipher.doFinal(b64UrlDecode(payloadStr)).decodeToString()
                    .removePrefix("\uFEFF").replace("\\/", "/")
                dbg("BYSE entschlüsselt: ${sourcesJson.length} Zeichen")
            }

            var found = false
            Regex("""\{[^{}]*\}""").findAll(sourcesJson).forEach { m ->
                val obj = m.value
                val u = jStr(obj, "url") ?: return@forEach
                if (!u.startsWith("http")) return@forEach
                if (!emitted.add(u)) return@forEach
                val h = Regex(""""height"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull()
                val lbl = jStr(obj, "label")
                val isM3u8 = u.contains(".m3u8") || obj.contains("mpegurl", true) || obj.contains("hls", true)

                dbg("BYSE LINK: $u")
                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Byse" + (lbl?.let { " $it" } ?: h?.let { " ${it}p" } ?: ""),
                    url = u,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://$host/"
                    this.quality = h ?: Qualities.P1080.value
                    this.headers = mapOf("Referer" to "https://$host/", "User-Agent" to byseUA)
                })
                found = true
            }
            return found
        } catch (e: Exception) {
            dbg("BYSE Fehler: ${e.message}")
            return false
        }
    }

    // ==================================================================

    private suspend fun renameLink(link: ExtractorLink, label: String): ExtractorLink =
        newExtractorLink(
            source = this.name,
            name = "$label (${link.name})",
            url = link.url,
            type = link.type
        ) {
            this.referer = link.referer
            this.quality = link.quality
            this.headers = link.headers
            this.extractorData = link.extractorData
        }

    private suspend fun genericMedia(
        url: String, referer: String, label: String,
        visited: MutableSet<String>, emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var ok = false
        try {
            val resp = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to referer))
            dbg("GENERIC ${resp.url} (${resp.text.length} Zeichen)")

            // NEU: externe URLs der Seite loggen (zeigt uns z.B. die Abys-API)
            val extern = collectCandidates(resp.document, resp.text)
                .filterNot { isOwnSite(it) || it == url || it == resp.url }
            dbg("GENERIC extern (${extern.size}): ${extern.take(8).joinToString(" | ")}")

            if (directVideos(resp.document, resp.text, url, label, emitted, callback)) ok = true

            val iframes = resp.document.select("iframe")
                .map { it.attr("src").ifEmpty { it.attr("data-src") } }
                .filter { it.isNotEmpty() }
                .map { fixUrl(it) }
                .filterNot { isJunk(it) || it == url || visited.contains(it) }

            for (f in iframes.take(4)) {
                visited.add(f)
                if (f.contains("byse", true) || f.contains("filemoon", true)) {
                    if (parseByse(f, emitted, callback)) ok = true
                    continue
                }
                val col = mutableListOf<ExtractorLink>()
                try { loadExtractor(f, url, subtitleCallback) { col.add(it) } } catch (_: Exception) {}
                col.forEach { link -> callback(renameLink(link, label)); ok = true }
                if (col.isEmpty()) {
                    try {
                        val r2 = app.get(f, headers = mapOf("User-Agent" to userAgent, "Referer" to url))
                        if (directVideos(r2.document, r2.text, f, label, emitted, callback)) ok = true
                    } catch (_: Exception) {}
                }
            }

            if (!ok && resp.url != url &&
                (resp.url.contains("byse", true) || resp.url.contains("filemoon", true))
            ) {
                if (parseByse(resp.url, emitted, callback)) ok = true
            }
        } catch (e: Exception) {
            dbg("GENERIC Fehler $url: ${e.message}")
        }
        return ok
    }

    private suspend fun handleServer(
        url: String, referer: String, label: String,
        visited: MutableSet<String>, emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        if (isJunk(url)) return
        dbg("SERVER: $url")

        // DSServer: ERSTE funktionierende Variante gewinnt, keine Duplikate mehr
        if (url.contains("dsplayer.site")) {
            var dsOk = false
            val variants = listOf(
                url,
                url.replace("/embed2/video.php?slug=", "/watch.php?v=") + ".mp4",
                url.replace("/embed2/video.php?slug=", "/watch.php?v=")
            )
            for (variant in variants) {
                if (dsOk) break
                try {
                    val r = app.get(variant, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
                    if (directVideos(r.document, r.text, variant, "$name - DSServer", emitted, callback)) dsOk = true
                } catch (_: Exception) {}
            }
            visited.add(url)
            return
        }

        if (!visited.add(url)) return

        if (url.contains("byse", true) || url.contains("filemoon", true) || url.contains("moonplayer", true)) {
            if (parseByse(url, emitted, callback)) return
        }

        val collected = mutableListOf<ExtractorLink>()
        try { loadExtractor(url, referer, subtitleCallback) { collected.add(it) } } catch (_: Exception) {}
        if (collected.isNotEmpty()) {
            dbg("EXTRACTOR OK: $url (${collected.size})")
            collected.forEach { link -> callback(renameLink(link, label)) }
            return
        }

        genericMedia(url, referer, label, visited, emitted, subtitleCallback, callback)
    }

    // ------------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val visited = mutableSetOf<String>()
        val emitted = mutableSetOf<String>()
        val wrapped: (ExtractorLink) -> Unit = { foundAny = true; callback(it) }

        try {
            dbg("=== LOADLINKS: $data ===")
            val page = app.get(data, headers = headers)
            val serverUrls = linkedSetOf<String>()
            serverUrls.addAll(extractServerUrls(page.text))

            val mainCands = collectCandidates(page.document, page.text)
            mainCands.filter { isOwnSite(it) && it != data && (it.contains(".php") || it.contains("/embed")) }
                .take(6)
                .forEach { p ->
                    if (!visited.add(p)) return@forEach
                    runCatching {
                        val r = app.get(p, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                        val found = extractServerUrls(r.text)
                        dbg("FRAME $p -> ${found.size} Server")
                        serverUrls.addAll(found)
                        if (found.isEmpty()) {
                            serverUrls.addAll(collectCandidates(r.document, r.text).filterNot { isOwnSite(it) })
                        }
                    }
                }

            if (serverUrls.isEmpty()) {
                serverUrls.addAll(mainCands.filterNot { isOwnSite(it) }.take(10))
            }

            dbg("SERVERLISTE (${serverUrls.size}):")
            serverUrls.forEach { dbg("  -> $it") }

            serverUrls.forEach { u ->
                runCatching {
                    handleServer(
                        u, data,
                        "$name - ${urlBase(u).substringAfter("//").substringBefore(".")}",
                        visited, emitted, subtitleCallback, wrapped
                    )
                }
            }

            // DSServer-Slug-Fallback NUR wenn bisher kein DSServer-Link gefunden wurde
            if (emitted.none { it.contains("dsplayer") }) {
                val pathSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
                val slugs = mutableSetOf<String>()
                if (pathSlug.isNotEmpty() && pathSlug != "film" && pathSlug != "serija") slugs.add(pathSlug)
                serverUrls.forEach { u ->
                    Regex("""[?&](?:search|v|slug|film)=([^&"'\s<>\\]+)""").findAll(u).forEach {
                        slugs.add(it.groupValues[1].removeSuffix(".mp4").trim())
                    }
                }
                outer@ for (slug in slugs) {
                    val cleanSlug = slug.removePrefix("rs-").removePrefix("glo-")
                    for (s in listOf(cleanSlug, "rs-$cleanSlug", "glo-$cleanSlug").distinct()) {
                        for (dsUrl in listOf(
                            "https://dsplayer.site/watch.php?v=$s.mp4",
                            "https://dsplayer.site/embed2/video.php?slug=$s"
                        )) {
                            if (!visited.add(dsUrl)) continue
                            runCatching {
                                val r = app.get(dsUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
                                if (directVideos(r.document, r.text, dsUrl, "$name - DSServer", emitted, wrapped)) break@outer
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dbg("LOADLINKS CRASH: ${e.message}")
        }

        dbg("=== ENDE: foundAny=$foundAny emitted=${emitted.size} ===")
        return foundAny || emitted.isNotEmpty()
    }
}
