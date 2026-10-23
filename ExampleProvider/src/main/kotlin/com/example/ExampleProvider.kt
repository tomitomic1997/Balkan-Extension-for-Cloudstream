@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class ExYuTeka : MainAPI() {
    override var mainUrl = "https://exyuteka.net"
    override var name = "ExYuTeka"
    override val hasMainPage = true
    override var lang = "un"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Najnovije",
        "$mainUrl/filmovi.php" to "Domaći Filmovi",
        "$mainUrl/serije.php" to "Domaće Serije",
        "$mainUrl/sinhronizovani.php" to "Sinhronizovani Filmovi"
    )

    private fun parseMoviesFromDoc(document: Document): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        val elements = document.select("a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/'], a[href*='/ep/'], a:has(img)")

        for (link in elements) {
            val href = fixUrlNull(link.attr("href")) ?: continue

            if (href == mainUrl || href == "$mainUrl/" || href.endsWith(".php") || href.endsWith("#")) continue
            if (href.contains("login") || href.contains("register") || href.contains("kontakt")) continue

            val card = link.parent() ?: link
            val img = link.selectFirst("img") ?: card.selectFirst("img") ?: card.parent()?.selectFirst("img") ?: continue

            val posterUrl = fixUrlNull(
                img.attr("data-src").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("src")
                    }
                }
            ) ?: continue

            if (posterUrl.contains("logo", ignoreCase = true) || posterUrl.contains("icon", ignoreCase = true)) continue

            val rawTitle = link.attr("title").trim().ifEmpty { null }
                ?: img.attr("alt").trim().ifEmpty { null }
                ?: card.selectFirst("h1, h2, h3, h4, h5, .title, .name, [class*='title']")?.text()?.trim()?.ifEmpty { null }
                ?: link.text().trim().ifEmpty { null }
                ?: href.substringAfterLast("/").replace("-", " ").trim()

            if (rawTitle.isEmpty()) continue

            val lowerTitle = rawTitle.lowercase()
            if (lowerTitle in listOf("početna", "pocetna", "filmovi", "serije", "najnovije", "home", "pretraga", "domaći filmovi", "domaće serije")) continue

            if (seenUrls.add(href)) {
                val isSeries = href.contains("/serija/") || href.contains("/gledaj-online/")
                val type = if (isSeries) TvType.TvSeries else TvType.Movie

                results.add(
                    newMovieSearchResponse(rawTitle, href, type) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?strana=$page"
        val document = app.get(url, headers = headers).document
        val items = parseMoviesFromDoc(document)
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrls = listOf(
            "$mainUrl/pretraga.php?q=${query.replace(" ", "+")}",
            "$mainUrl/?s=${query.replace(" ", "+")}"
        )

        for (url in searchUrls) {
            try {
                val document = app.get(url, headers = headers).document
                val results = parseMoviesFromDoc(document)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, h2, .entry-title, .movie-title, .title")?.text()?.trim() ?: "ExYuTeka"
        val posterUrl = fixUrlNull(
            document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img[src*='upload'], img[src*='poster'], img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img[src*='upload'], img[src*='poster'], img")?.attr("src")
        )
        val rawPlot = document.selectFirst(".plot, .description, .story, #description, .entry-content p, p")?.text()?.trim() ?: ""
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(document.text())?.value?.toIntOrNull()

        // Auf ExYuTeka nutzen Episoden das Format /ep/name-s01e01
        val episodeElements = document.select("a[href*='/ep/'], a[href*='epizod']")
            .filter {
                val epHref = fixUrlNull(it.attr("href")) ?: ""
                epHref.isNotEmpty() && epHref != url && !it.text().contains("Prethodna") && !it.text().contains("Sledeća")
            }

        val isSeriesPage = url.contains("/serija/") || url.contains("/gledaj-online/") || ( !url.contains("/ep/") && episodeElements.size > 1 )

        // Diagnose für die erste Zeile der Beschreibung (sofort am TV lesbar)
        val iframes = document.select("iframe").mapNotNull { it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { null } }
        val dataAttrs = document.select("[data-src], [data-url], [data-embed], [data-player], [data-video], [data-iframe], [onclick*='player'], [onclick*='Server'], [onclick*='plejer']")
            .mapNotNull { el ->
                el.attr("data-embed").ifEmpty {
                    el.attr("data-url").ifEmpty {
                        el.attr("data-player").ifEmpty {
                            el.attr("data-video").ifEmpty {
                                el.attr("data-iframe").ifEmpty {
                                    el.attr("onclick").ifEmpty { el.attr("data-src") }
                                }
                            }
                        }
                    }
                }.ifEmpty { null }
            }.filter { !it.endsWith(".jpg") && !it.endsWith(".png") && !it.endsWith(".webp") }.take(4)

        val diagHeader = buildString {
            append("[DIAG] ")
            if (isSeriesPage) {
                append("Serie erkannt (${episodeElements.size} Folgen) | ")
            } else {
                append("Iframes: ${if (iframes.isEmpty()) "0" else iframes.joinToString()} | ")
                append("Player-Daten: ${if (dataAttrs.isEmpty()) "0" else dataAttrs.joinToString()} | ")
            }
            append("\n\n")
            append(rawPlot)
        }

        if (isSeriesPage && episodeElements.isNotEmpty()) {
            val episodes = episodeElements.mapIndexed { index, epElem ->
                val epHref = fixUrl(epElem.attr("href"))
                val epText = epElem.text().trim()

                // Staffel & Episode aus URL (z.B. -s01e02) oder Text auslesen
                val seMatch = Regex("""[sS](\d+)[eE](\d+)""").find(epHref) ?: Regex("""[sS](\d+)[eE](\d+)""").find(epText)
                val seasonNum = seMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""[sS]ezona\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                val episodeNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""[eE]pizoda\s*(\d+)|[eE](\d+)""").find(epText)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                    ?: (index + 1)

                val cleanName = epText.ifEmpty { "Epizoda $episodeNum" }

                newEpisode(epHref) {
                    this.name = cleanName
                    this.season = seasonNum
                    this.episode = episodeNum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = diagHeader
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = diagHeader
            this.year = year
        }
    }

    private suspend fun extractStreams(
        doc: Document,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. Direkte Video-Tags
        doc.select("video#player, video, video source").forEach { v ->
            val vUrl = v.attr("data-src").ifEmpty { v.attr("src") }
            if (vUrl.isNotEmpty() && !vUrl.startsWith("blob:")) {
                val fullUrl = fixUrl(vUrl)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fullUrl,
                        type = if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }
        }

        // 2. Direkte .m3u8 / .mp4 Links im Quelltext
        val html = doc.html()
        val streamRegexes = listOf(
            Regex("""https?://cdn\.dsplayer\.site[^\s"'<>\\]+"""),
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
        )

        for (regex in streamRegexes) {
            for (match in regex.findAll(html)) {
                val streamUrl = match.value.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }
        }

        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        val document = app.get(data, headers = headers).document
        val rawHtml = document.html()

        // 1. Direkt auf der Film-/Episodenseite nach Streams suchen
        if (extractStreams(document, data, subtitleCallback, callback)) {
            success = true
        }

        val candidateUrls = mutableSetOf<String>()

        // 2. Alle Iframes sammeln
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("facebook") && !src.startsWith("about:")) {
                candidateUrls.add(fixUrl(src))
            }
        }

        // 3. Alle Player-Buttons / Server-Umschalter auslesen
        document.select("[data-embed], [data-url], [data-player], [data-video], [data-iframe], [data-src], [onclick]").forEach { elem ->
            val attrs = listOf(
                elem.attr("data-embed"),
                elem.attr("data-url"),
                elem.attr("data-player"),
                elem.attr("data-video"),
                elem.attr("data-iframe"),
                elem.attr("data-src"),
                elem.attr("onclick")
            )
            for (attr in attrs) {
                if (attr.isEmpty()) continue
                if (attr.startsWith("http") || attr.startsWith("//") || attr.startsWith("/embed") || attr.startsWith("/player")) {
                    if (!attr.endsWith(".jpg") && !attr.endsWith(".png") && !attr.endsWith(".webp") && !attr.endsWith(".js")) {
                        candidateUrls.add(fixUrl(attr))
                    }
                }
                val urlInAttr = Regex("""(https?:)?//[^\s"'<>\\)]+""").find(attr)?.value
                if (urlInAttr != null && !urlInAttr.endsWith(".jpg") && !urlInAttr.endsWith(".png")) {
                    candidateUrls.add(fixUrl(urlInAttr))
                }
            }
        }

        // 4. Base64-codierte Player-URLs (starten mit "aHR0c") im HTML entschlüsseln
        val b64Regex = Regex("""aHR0c[A-Za-z0-9+/=]{10,}""")
        for (match in b64Regex.findAll(rawHtml)) {
            try {
                val decoded = String(Base64.decode(match.value, Base64.DEFAULT)).trim()
                if (decoded.startsWith("http")) {
                    candidateUrls.add(decoded)
                }
            } catch (_: Exception) {}
        }

        // 5. Alle externen Embed-URLs in <script>-Blöcken finden
        val scriptText = document.select("script").joinToString("\n") { it.html() }
        val scriptUrlRegex = Regex("""["']((?:https?:)?//[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/[^"'\s<>\\]+)["']""")
        for (match in scriptUrlRegex.findAll(scriptText)) {
            val foundUrl = fixUrl(match.groupValues[1].replace("\\/", "/"))
            val lower = foundUrl.lowercase()
            val isIgnored = lower.contains("exyuteka.net") && (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp"))
                || lower.contains("google") || lower.contains("facebook") || lower.contains("cloudflare") || lower.contains("jsdelivr") || lower.contains("jquery") || lower.contains("schema.org") || lower.contains("w3.org")
            if (!isIgnored) {
                candidateUrls.add(foundUrl)
            }
        }

        // 6. Jeden gefundenen Player-Link testen
        for (targetUrl in candidateUrls) {
            if (targetUrl == data) continue

            // Standard Cloudstream Extractor (OK.ru, Vidhide, Filemoon, Streamtape, Voe, etc.)
            try {
                if (loadExtractor(targetUrl, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}

            // Eigene Extraktion + verschachtelte Iframes prüfen
            try {
                val playerDoc = app.get(targetUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                if (extractStreams(playerDoc, targetUrl, subtitleCallback, callback)) {
                    success = true
                }

                playerDoc.select("iframe").forEach { nested ->
                    val nestedSrc = nested.attr("data-src").ifEmpty { nested.attr("src") }
                    if (nestedSrc.isNotEmpty() && !nestedSrc.startsWith("about:")) {
                        val fullNested = fixUrl(nestedSrc)
                        if (loadExtractor(fullNested, targetUrl, subtitleCallback, callback)) {
                            success = true
                        }
                        val nestedDoc = app.get(fullNested, headers = mapOf("User-Agent" to userAgent, "Referer" to targetUrl)).document
                        if (extractStreams(nestedDoc, fullNested, subtitleCallback, callback)) {
                            success = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return success
    }
}
