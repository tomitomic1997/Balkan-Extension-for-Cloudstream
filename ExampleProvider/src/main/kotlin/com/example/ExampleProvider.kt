@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
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
                section.select("a.card, div.card").forEach { parseCardElement(it)?.let { r -> results.add(r) } }
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
        "googletag", "managertechnically", "al5sm", "doubleclick", "googletagmanager",
        "google-analytics", "gstatic", "googleapis", "fonts.", "histats", "exoclick",
        "popads", "juicyads", "adsco", "favicon", "placeholder", "facebook.com", "twitter.com"
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

    private fun forward(link: ExtractorLink, label: String, callback: (ExtractorLink) -> Unit) {
        val renamed = runBlocking {
            newExtractorLink(
                source = this@ExYuTeka.name,
                name = label,
                url = link.url,
                type = link.type
            ) {
                this.referer = link.referer
                this.quality = link.quality
                this.headers = link.headers
                this.extractorData = link.extractorData
            }
        }
        callback(renamed)
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

        doc.select("iframe, frame, embed, source, video").forEach { add(it.attr("src")); add(it.attr("data-src")) }
        doc.select("[data-src], [data-url], [data-embed], [data-play-url], [data-server], [data-link], [data-file]")
            .forEach { el ->
                listOf("data-src", "data-url", "data-embed", "data-play-url", "data-server", "data-link", "data-file")
                    .forEach { add(el.attr(it)) }
            }
        doc.select("a[href]").forEach { add(it.attr("href")) }

        val jsTargets = Regex("""(?:\.src|location(?:\.href)?|window\.open)\s*(?:=|\()\s*["']([^"']+)["']""")
        jsTargets.findAll(html).forEach { add(it.groupValues[1]) }
        doc.select("[onclick]").forEach { el ->
            jsTargets.findAll(el.attr("onclick")).forEach { add(it.groupValues[1]) }
            Regex("""["']([^"']*(?:\.php|/e/|/embed/|/v/)[^"']*)["']""")
                .findAll(el.attr("onclick")).forEach { add(it.groupValues[1]) }
        }
        doc.select("meta[http-equiv=refresh]").forEach { m ->
            Regex("""url\s*=\s*['"]?([^'";]+)""", RegexOption.IGNORE_CASE)
                .find(m.attr("content"))?.let { add(it.groupValues[1]) }
        }
        Regex("""["'](/(?:embed|exyuvid|player|video)[^"'\s<>]*)["']""").findAll(html).forEach { add(it.groupValues[1]) }
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
            if (u.contains(".php") || u.contains("/watch") || u.contains("/embed")) continue
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

    // ------------------------------------------------ Byse (ehemals Filemoon) Extractor

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return Base64.decode(fixed + "=".repeat(pad), Base64.DEFAULT)
    }

    /**
     * Byse = neues Filemoon. Funktioniert auch mit unbekannten Mirror-Domains (z.B. n1mwq.org),
     * die Cloudstreams eingebauter ByseSX-Extractor nicht kennt.
     */
    private suspend fun parseByse(
        embedUrl: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!embedUrl.startsWith("http")) return false
        val base = urlBase(embedUrl)
        val code = urlCode(embedUrl)
        if (code.length < 4) return false

        try {
            // Schritt 1: details -> embed_frame_url
            val detailsText = app.get(
                "$base/api/videos/$code/embed/details",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$base/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text.replace("\\/", "/")

            val frameUrl = Regex(""""embed_frame_url"\s*:\s*"([^"]+)"""")
                .find(detailsText)?.groupValues?.get(1) ?: return false

            val embedBase = urlBase(frameUrl)
            val embedCode = urlCode(frameUrl)

            // Schritt 2: playback -> AES-GCM Payload
            val pbText = app.get(
                "$embedBase/api/videos/$embedCode/embed/playback",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "accept" to "*/*",
                    "accept-language" to "en-US,en;q=0.5",
                    "referer" to frameUrl,
                    "x-embed-parent" to embedUrl
                )
            ).text

            val partsBlock = Regex(""""key_parts"\s*:\s*\[([^\]]*)\]""").find(pbText)?.groupValues?.get(1)
                ?: return false
            val parts = Regex(""""([^"]+)"""").findAll(partsBlock).map { it.groupValues[1] }.toList()
            if (parts.size < 2) return false

            val ivStr = Regex(""""iv"\s*:\s*"([^"]+)"""").find(pbText)?.groupValues?.get(1) ?: return false
            val payloadStr = Regex(""""payload"\s*:\s*"([^"]+)"""").find(pbText)?.groupValues?.get(1) ?: return false

            val keyBytes = b64UrlDecode(parts[0]) + b64UrlDecode(parts[1])
            val ivBytes = b64UrlDecode(ivStr)
            val cipherBytes = b64UrlDecode(payloadStr)

            // Schritt 3: AES-256-GCM entschlüsseln
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
            val plain = cipher.doFinal(cipherBytes).decodeToString().removePrefix("\uFEFF").replace("\\/", "/")

            // Schritt 4: Quellen auslesen
            var found = false
            Regex("""\{[^{}]*\}""").findAll(plain).forEach { m ->
                val obj = m.value
                val u = Regex(""""url"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1) ?: return@forEach
                if (!u.startsWith("http")) return@forEach
                if (!emitted.add(u)) return@forEach
                val h = Regex(""""height"\s*:\s*(\d+)""").find(obj)?.groupValues?.get(1)?.toIntOrNull()
                val lbl = Regex(""""label"\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
                val isM3u8 = u.contains(".m3u8") || obj.contains("mpegurl", true) || obj.contains("hls", true)

                callback(newExtractorLink(
                    source = this.name,
                    name = "$name - Byse" + (lbl?.let { " $it" } ?: h?.let { " ${it}p" } ?: ""),
                    url = u,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$embedBase/"
                    this.quality = h ?: Qualities.P1080.value
                    this.headers = mapOf("Referer" to "$embedBase/", "User-Agent" to userAgent)
                })
                found = true
            }
            return found
        } catch (_: Exception) {
            return false
        }
    }

    /** Erst Cloudstreams Extractoren, dann Byse-Fallback für unbekannte Domains. */
    private suspend fun tryHoster(
        url: String,
        referer: String,
        label: String,
        visited: MutableSet<String>,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isJunk(url) || !visited.add(url)) return

        // Bekannte Byse-Domain? Direkt eigener Extractor (liefert alle Qualitäten)
        if (url.contains("byse", true) || url.contains("filemoon", true)) {
            if (parseByse(url, emitted, callback)) return
        }

        var got = false
        val cb: (ExtractorLink) -> Unit = { got = true; forward(it, label, callback) }
        try {
            val finalUrl = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to referer)).url
            if (finalUrl.isNotEmpty() && finalUrl != url && !isOwnSite(finalUrl) && visited.add(finalUrl)) {
                loadExtractor(finalUrl, referer, subtitleCallback, cb)
                if (!got) parseByse(finalUrl, emitted, callback)
            }
            loadExtractor(url, referer, subtitleCallback, cb)
        } catch (_: Exception) {
            try { loadExtractor(url, referer, subtitleCallback, cb) } catch (_: Exception) {}
        }

        // Kein Extractor hat gepasst -> Byse-API试探 (neue Mirror-Domains wie n1mwq.org)
        if (!got) parseByse(url, emitted, callback)
    }

    // ------------------------------------------------------------------ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false
        val visited = mutableSetOf<String>()
        val emitted = mutableSetOf<String>()
        val wrapped: (ExtractorLink) -> Unit = { foundAny = true; callback(it) }

        val page = app.get(data, headers = headers)
        val mainCandidates = collectCandidates(page.document, page.text)

        // 1. DSPlayer über Slugs
        val slugs = mutableSetOf<String>()
        val pathSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
        if (pathSlug.isNotEmpty() && pathSlug != "film" && pathSlug != "serija") slugs.add(pathSlug)
        mainCandidates.forEach { u ->
            Regex("""[?&](?:search|v|slug|film)=([^&"'\s<>\\]+)""").findAll(u).forEach {
                slugs.add(it.groupValues[1].removeSuffix(".mp4").trim())
            }
        }
        var dsFound = false
        for (slug in slugs) {
            if (dsFound) break
            val cleanSlug = slug.removePrefix("rs-").removePrefix("glo-")
            for (s in listOf(cleanSlug, "rs-$cleanSlug", "glo-$cleanSlug").distinct()) {
                if (dsFound) break
                for (dsUrl in listOf(
                    "https://dsplayer.site/watch.php?v=$s.mp4",
                    "https://dsplayer.site/watch.php?v=$s",
                    "https://dsplayer.site/embed2/video.php?slug=$s",
                    "https://dsplayer.site/video.php?slug=$s"
                )) {
                    if (!visited.add(dsUrl)) continue
                    try {
                        val r = app.get(dsUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
                        if (directVideos(r.document, r.text, dsUrl, "$name - DSServer", emitted, wrapped)) {
                            dsFound = true; break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. Server-Tab-Varianten
        val playerPages = linkedSetOf<String>()
        mainCandidates.forEach { u ->
            if (isOwnSite(u) && (u.contains("/embed/") || u.contains("/exyuvid/") || u.contains(".php"))) playerPages.add(u)
        }
        playerPages.toList().forEach { u ->
            if (u.contains(Regex("""sro\d*\.php"""))) {
                for (n in 1..8) playerPages.add(u.replace(Regex("""sro\d*\.php"""), "sro$n.php"))
            }
            if (u.contains("srofilm.php")) {
                for (n in 1..5) {
                    playerPages.add(u.replace("srofilm.php", "srofilm$n.php"))
                    playerPages.add(u.replace("srofilm.php", "sro$n.php"))
                }
            }
        }

        fun priority(u: String) = when {
            u.contains("byse", true) || u.contains("filemoon", true) -> 4
            u.contains("/e/") || u.contains("/embed/") -> 3
            Regex("""/[a-z0-9]{3,}/[a-z0-9]{6,}""", RegexOption.IGNORE_CASE).containsMatchIn(u) -> 2
            else -> 1
        }

        // 3. Externe Hoster auf der Hauptseite
        mainCandidates.filterNot { isOwnSite(it) || it.contains("dsplayer") }
            .sortedByDescending { priority(it) }.take(14)
            .forEach { tryHoster(it, data, "$name - ${urlBase(it).substringAfter("//")}", visited, emitted, subtitleCallback, wrapped) }

        // 4. Interne Player-Seiten
        var budget = 28
        for (pUrl in playerPages) {
            if (budget <= 0) break
            if (!visited.add(pUrl)) continue
            budget--
            try {
                val resp = app.get(pUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val finalUrl = resp.url
                val label = "$name - " + pUrl.substringAfterLast("/").substringBefore(".php").uppercase()

                if (finalUrl.isNotEmpty() && finalUrl != pUrl && !isOwnSite(finalUrl)) {
                    tryHoster(finalUrl, data, label, visited, emitted, subtitleCallback, wrapped)
                    continue
                }

                val subHtml = resp.text
                val subDoc = resp.document
                val subCands = collectCandidates(subDoc, subHtml)

                if (pUrl.contains("dsplayer") || subCands.any { it.contains("cdn.dsplayer") }) {
                    if (directVideos(subDoc, subHtml, pUrl, "$name - DSServer", emitted, wrapped)) dsFound = true
                }
                directVideos(subDoc, subHtml, pUrl, label, emitted, wrapped)

                subCands.filterNot { isOwnSite(it) }
                    .sortedByDescending { priority(it) }.take(14)
                    .forEach { u ->
                        if (u.contains("dsplayer")) {
                            if (visited.add(u)) {
                                try {
                                    val r = app.get(u, headers = mapOf("User-Agent" to userAgent, "Referer" to pUrl))
                                    directVideos(r.document, r.text, u, "$name - DSServer", emitted, wrapped)
                                } catch (_: Exception) {}
                            }
                        } else {
                            tryHoster(u, pUrl, "$name - ${urlBase(u).substringAfter("//")}", visited, emitted, subtitleCallback, wrapped)
                        }
                    }

                subCands.filter { isOwnSite(it) && it != pUrl && (it.contains(".php") || it.contains("/embed")) }
                    .take(4).forEach inner@{ inner ->
                        if (!visited.add(inner)) return@inner
                        try {
                            val r2 = app.get(inner, headers = mapOf("User-Agent" to userAgent, "Referer" to pUrl))
                            if (!isOwnSite(r2.url) && r2.url != inner) {
                                tryHoster(r2.url, pUrl, label, visited, emitted, subtitleCallback, wrapped)
                            } else {
                                collectCandidates(r2.document, r2.text).filterNot { isOwnSite(it) }
                                    .sortedByDescending { priority(it) }.take(8)
                                    .forEach { tryHoster(it, inner, label, visited, emitted, subtitleCallback, wrapped) }
                                directVideos(r2.document, r2.text, inner, label, emitted, wrapped)
                            }
                        } catch (_: Exception) {}
                    }
            } catch (_: Exception) {}
        }

        return foundAny || emitted.isNotEmpty()
    }
}
