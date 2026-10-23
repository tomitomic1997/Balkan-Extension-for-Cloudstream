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
        "$mainUrl/filmovi.php" to "Domaći Filmovi",
        "$mainUrl/serije.php" to "Domaće Serije",
        "$mainUrl/sinhronizovani.php" to "Sinhronizovani Filmovi"
    )

    private fun parseCards(document: Document): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        // Findet alle Karten und Film-Links
        val cards = document.select("a.card, a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/']")

        for (link in cards) {
            val href = fixUrlNull(link.attr("href")) ?: continue
            if (href == mainUrl || href == "$mainUrl/" || href.endsWith(".php") || href.endsWith("#")) continue

            val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img") ?: continue
            val posterUrl = fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") }) ?: continue
            if (posterUrl.contains("logo") || posterUrl.contains("placeholder")) continue

            val title = link.selectFirst(".card-title, h1, h2, h3, .title")?.text()?.trim()
                ?: img.attr("alt").trim().ifEmpty { null }
                ?: link.attr("title").trim().ifEmpty { null }
                ?: continue

            if (title.lowercase() in listOf("početna", "filmovi", "serije", "najnovije")) continue

            if (seenUrls.add(href)) {
                val isSeries = href.contains("/serija/") || href.contains("/gledaj-online/")
                val type = if (isSeries) TvType.TvSeries else TvType.Movie

                results.add(
                    newMovieSearchResponse(title, href, type) {
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
        val items = parseCards(document)
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
                val results = parseCards(document)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Exception) {}
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst(".single-title, h1, .title")?.text()?.trim() ?: "ExYuTeka"
        val posterUrl = fixUrlNull(
            document.selectFirst(".single-poster img, .poster img, img[src*='/slika-']")?.attr("data-src")
                ?: document.selectFirst(".single-poster img, .poster img, img[src*='/slika-']")?.attr("src")
        )
        val plot = document.selectFirst(".single-desc, .description, p")?.text()?.trim()
        val year = document.selectFirst(".single-meta")?.text()?.let {
            Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }

        // Glasklare Trennung: Ist /film/ in der URL, ist es IMMER ein Film!
        val isMovie = url.contains("/film/")

        if (!isMovie) {
            // Nur bei Serien nach Episoden suchen
            val episodeElements = document.select("a[href*='/ep/'], a[href*='epizod'], div.episodes a, div.epizode a")
                .filter { it.attr("href").isNotEmpty() && fixUrl(it.attr("href")) != url }

            if (episodeElements.isNotEmpty()) {
                val episodes = episodeElements.mapIndexed { index, epElem ->
                    val epHref = fixUrl(epElem.attr("href"))
                    val epText = epElem.text().trim()

                    val seMatch = Regex("""[sS](\d+)[eE](\d+)""").find(epHref) ?: Regex("""[sS](\d+)[eE](\d+)""").find(epText)
                    val seasonNum = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episodeNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                        ?: Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                        ?: (index + 1)

                    newEpisode(epHref) {
                        this.name = epText.ifEmpty { "Epizoda $episodeNum" }
                        this.season = seasonNum
                        this.episode = episodeNum
                    }
                }.distinctBy { it.data }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                }
            }
        }

        // Standard: Als Spielfilm laden
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun scanDocForVideos(
        doc: Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // 1. Direkte Video-Tags
        doc.select("video source, video").forEach { v ->
            val src = v.attr("src").ifEmpty { v.attr("data-src") }
            if (src.isNotEmpty() && !src.startsWith("blob:")) {
                val fullUrl = fixUrl(src)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = fullUrl,
                        type = if (fullUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                found = true
            }
        }

        // 2. Stream-URLs im HTML/JavaScript-Text
        val html = doc.html()
        val regexList = listOf(
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
            Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
            Regex("""https?://cdn\.dsplayer\.site[^\s"'<>\\]+""")
        )

        for (regex in regexList) {
            for (match in regex.findAll(html)) {
                val streamUrl = match.value.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pageUrl
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

        // Alle Iframes auf der Film- oder Episodenseite sammeln (z.B. /embed/srofilm.php?search=...)
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                fixUrl(src)
            } else null
        }

        for (embedUrl in iframes) {
            // Erst Standard-Hoster (Streamtape, Filemoon etc.) probieren
            try {
                if (loadExtractor(embedUrl, data, subtitleCallback, callback)) {
                    foundAny = true
                }
            } catch (_: Exception) {}

            // Den ExYuTeka-Embed-Player (/embed/srofilm.php) abrufen
            try {
                val embedDoc = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document

                // Direkt nach Videos im Embed suchen
                if (scanDocForVideos(embedDoc, embedUrl, subtitleCallback, callback)) {
                    foundAny = true
                }

                // Falls der Embed-Player wiederum ein weiteres Iframe zu einem Hoster enthält
                embedDoc.select("iframe").forEach { innerIframe ->
                    val innerSrc = innerIframe.attr("src").ifEmpty { innerIframe.attr("data-src") }
                    if (innerSrc.isNotEmpty()) {
                        val fullInner = fixUrl(innerSrc)
                        try {
                            if (loadExtractor(fullInner, embedUrl, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        } catch (_: Exception) {}

                        try {
                            val innerDoc = app.get(fullInner, headers = mapOf("User-Agent" to userAgent, "Referer" to embedUrl)).document
                            if (scanDocForVideos(innerDoc, fullInner, subtitleCallback, callback)) {
                                foundAny = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        return foundAny
    }
}
