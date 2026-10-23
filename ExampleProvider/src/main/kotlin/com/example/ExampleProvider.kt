@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

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
        "$mainUrl/filmovi.php" to "Filmovi",
        "$mainUrl/serije.php" to "Serije"
    )

    private fun parseMoviesFromDoc(document: Document): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        val elements = document.select("a:has(img), div:has(img) > a, article a, [class*='movie'] a, [class*='film'] a, [class*='card'] a")

        for (link in elements) {
            val href = fixUrlNull(link.attr("href")) ?: continue

            if (href == mainUrl || href == "$mainUrl/" || href.endsWith("index.php") || href.endsWith("#")) continue
            if (href.contains("login") || href.contains("register") || href.contains("kontakt")) continue

            val card = link.parent() ?: link
            val img = link.selectFirst("img") ?: card.selectFirst("img") ?: continue

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
                ?: card.selectFirst("h1, h2, h3, h4, h5, .title, .name, [class*='title'], p, span")?.text()?.trim()?.ifEmpty { null }
                ?: link.text().trim().ifEmpty { null }
                ?: href.substringAfterLast("/").substringBefore(".php").substringBefore(".html").replace("-", " ").replace("_", " ").trim()

            if (rawTitle.isEmpty()) continue

            val lowerTitle = rawTitle.lowercase()
            if (lowerTitle in listOf("početna", "pocetna", "filmovi", "serije", "najnovije", "home", "pretraga")) continue

            if (seenUrls.add(href)) {
                val isSeries = href.contains("serij", ignoreCase = true) || lowerTitle.contains("serija") || lowerTitle.contains("sezona")
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
        var document = app.get(request.data, headers = headers).document
        var items = parseMoviesFromDoc(document)

        if (items.isEmpty() && request.data.endsWith(".php")) {
            val altUrl = request.data.removeSuffix(".php") + "/"
            try {
                document = app.get(altUrl, headers = headers).document
                items = parseMoviesFromDoc(document)
            } catch (_: Exception) {}
        }

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

        return try {
            val homeDoc = app.get("$mainUrl/", headers = headers).document
            parseMoviesFromDoc(homeDoc).take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, h2, .entry-title, .movie-title, .title")?.text()?.trim() ?: "ExYuTeka Video"
        val posterUrl = fixUrlNull(
            document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("src")
        )
        val rawPlot = document.selectFirst(".plot, .description, .story, #description, .entry-content p, p")?.text()?.trim() ?: ""
        val year = document.selectFirst(".year, .release-date, span[class*='year']")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        // Diagnose-Hilfe: Zeigt gefundene Iframes/Videos direkt im Beschreibungstext am TV an
        val foundIframes = document.select("iframe").mapNotNull {
            it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { null }
        }
        val foundVideos = document.select("video, video source").mapNotNull {
            it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { null }
        }
        val debugInfo = buildString {
            if (rawPlot.isNotEmpty()) append(rawPlot).append("\n\n")
            append("[Diagnose] URL: ").append(url)
            if (foundIframes.isNotEmpty()) append("\nIframes: ").append(foundIframes.joinToString(", "))
            if (foundVideos.isNotEmpty()) append("\nVideos: ").append(foundVideos.joinToString(", "))
            if (foundIframes.isEmpty() && foundVideos.isEmpty()) {
                val watchLinks = document.select("a[href*='gledaj'], a[href*='watch'], a[href*='epizod'], a[href*='player'], a.btn").mapNotNull { it.attr("href").ifEmpty { null } }.take(5)
                if (watchLinks.isNotEmpty()) append("\nButtons/Links: ").append(watchLinks.joinToString(", "))
                else append("\nKeine direkten Iframes im HTML gefunden.")
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = debugInfo
            this.year = year
        }
    }

    private suspend fun extractFromDoc(
        doc: Document,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. Direkte <video> und <source> Elemente prüfen
        val videoElements = doc.select("video#player, video, video source")
        for (v in videoElements) {
            val vUrl = v.attr("data-src").ifEmpty { v.attr("src") }
            if (vUrl.isNotEmpty()) {
                val fullVideoUrl = fixUrl(vUrl)
                val isM3u8 = fullVideoUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fullVideoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = refererUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }
        }

        // 2. Im gesamten HTML-Quelltext nach .m3u8, .mp4 oder dsplayer-Streams suchen
        val html = doc.html()
        val regexList = listOf(
            Regex("""https?://cdn\.dsplayer\.site[^\s"'<>\\]+"""),
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
        )
        for (regex in regexList) {
            for (match in regex.findAll(html)) {
                val streamUrl = match.value.replace("\\/", "/")
                val isM3u8 = streamUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
        var foundAny = false
        val document = app.get(data, headers = headers).document

        // 1. Direkt auf der Hauptseite nach Streams suchen
        if (extractFromDoc(document, data, subtitleCallback, callback)) {
            foundAny = true
        }

        // 2. Alle Iframes aus HTML und <script>-Blöcken sammeln
        val iframeUrls = mutableSetOf<String>()
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty() && !src.contains("googletagmanager") && !src.contains("facebook")) {
                iframeUrls.add(fixUrl(src))
            }
        }

        val iframeRegex = Regex("""(?:src|file|embed_url)\s*[:=]\s*["'](https?://[^"']+)["']""")
        for (match in iframeRegex.findAll(document.html())) {
            val url = match.groupValues[1].replace("\\/", "/")
            if (!url.endsWith(".js") && !url.endsWith(".css") && !url.endsWith(".png") && !url.endsWith(".jpg")) {
                iframeUrls.add(url)
            }
        }

        // 3. Jedes gefundene Iframe über Cloudstream-Extractor UND eigenen Parser prüfen
        for (embedUrl in iframeUrls) {
            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {}

            try {
                val playerDoc = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                if (extractFromDoc(playerDoc, embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                }

                // Falls im Iframe noch ein weiteres Iframe steckt (verschachtelter Player)
                playerDoc.select("iframe").forEach { nested ->
                    val nestedSrc = nested.attr("data-src").ifEmpty { nested.attr("src") }
                    if (nestedSrc.isNotEmpty()) {
                        val fullNested = fixUrl(nestedSrc)
                        if (loadExtractor(fullNested, embedUrl, subtitleCallback, callback)) {
                            foundAny = true
                        }
                        val nestedDoc = app.get(fullNested, headers = mapOf("User-Agent" to userAgent, "Referer" to embedUrl)).document
                        if (extractFromDoc(nestedDoc, fullNested, subtitleCallback, callback)) {
                            foundAny = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }
}
