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
                val isSeries = href.contains("/serij", ignoreCase = true) || lowerTitle.startsWith("serija ")
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

        // Serien-Erkennung NUR, wenn die Adresse explizit "serij" enthält
        val isExplicitSeries = url.contains("/serij", ignoreCase = true)
        val episodeLinks = if (isExplicitSeries) {
            document.select("a[href*='epizoda'], a[href*='ep-'], div.epizode a, ul.episodios a")
                .filter { it.attr("href").isNotEmpty() && fixUrl(it.attr("href")) != url }
        } else {
            emptyList()
        }

        // Diagnose-Zeile direkt auf dem TV-Bildschirm
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("facebook")) fixUrl(src) else null
        }
        val buttons = document.select("a[href*='gledaj'], a[href*='watch'], a[href*='player'], [data-url], [data-embed]").mapNotNull {
            val target = it.attr("data-embed").ifEmpty { it.attr("data-url").ifEmpty { it.attr("href") } }
            if (target.isNotEmpty() && !target.startsWith("#") && !target.startsWith("javascript")) fixUrl(target) else null
        }.take(3)

        val debugPlot = buildString {
            if (rawPlot.isNotEmpty()) append(rawPlot).append("\n\n")
            append("--- [DIAGNOSE] ---\n")
            append("Typ: ").append(if (episodeLinks.isNotEmpty()) "Serie" else "Film").append("\n")
            append("URL: ").append(url).append("\n")
            if (iframes.isNotEmpty()) append("Iframes: ").append(iframes.joinToString(", ")).append("\n")
            if (buttons.isNotEmpty()) append("Player-Buttons: ").append(buttons.joinToString(", ")).append("\n")
            if (iframes.isEmpty() && buttons.isEmpty()) append("Hinweis: Kein Iframe und kein Button direkt im HTML gefunden.\n")
        }

        if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { index, epElem ->
                val epHref = fixUrl(epElem.attr("href"))
                val epName = epElem.text().trim().ifEmpty { "Epizoda ${index + 1}" }
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull() ?: (index + 1)
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = debugPlot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = debugPlot
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
            if (vUrl.isNotEmpty()) {
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

        // 2. Stream-URLs im HTML-Quelltext
        val html = doc.html()
        val regexPatterns = listOf(
            Regex("""https?://cdn\.dsplayer\.site[^\s"'<>\\]+"""),
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
        )

        for (regex in regexPatterns) {
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

        // Direkt auf der Seite suchen
        if (extractStreams(document, data, subtitleCallback, callback)) {
            success = true
        }

        // Alle Player-Links einsammeln (Iframes & Buttons)
        val targets = mutableSetOf<String>()

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("facebook")) {
                targets.add(fixUrl(src))
            }
        }

        document.select("a[href*='gledaj'], a[href*='watch'], a[href*='player'], [data-url], [data-embed]").forEach { elem ->
            val target = elem.attr("data-embed").ifEmpty {
                elem.attr("data-url").ifEmpty {
                    elem.attr("data-src").ifEmpty { elem.attr("href") }
                }
            }
            if (target.isNotEmpty() && !target.startsWith("#") && !target.startsWith("javascript")) {
                val fullTarget = fixUrl(target)
                if (fullTarget != data) {
                    targets.add(fullTarget)
                }
            }
        }

        // Gefundene URLs abfragen
        for (url in targets) {
            try {
                if (loadExtractor(url, data, subtitleCallback, callback)) {
                    success = true
                }
            } catch (_: Exception) {}

            try {
                val targetDoc = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                if (extractStreams(targetDoc, url, subtitleCallback, callback)) {
                    success = true
                }

                targetDoc.select("iframe").forEach { nested ->
                    val nestedSrc = nested.attr("data-src").ifEmpty { nested.attr("src") }
                    if (nestedSrc.isNotEmpty()) {
                        val fullNested = fixUrl(nestedSrc)
                        if (loadExtractor(fullNested, url, subtitleCallback, callback)) {
                            success = true
                        }
                        val nestedDoc = app.get(fullNested, headers = mapOf("User-Agent" to userAgent, "Referer" to url)).document
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
