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

        val isMovie = url.contains("/film/")

        if (!isMovie) {
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    private suspend fun addStreamLink(
        rawUrl: String,
        refererUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = fixUrl(rawUrl.replace("\\/", "/").trim())
        if (streamUrl.isEmpty() || streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") || streamUrl.endsWith(".js") || streamUrl.endsWith(".css")) {
            return false
        }

        val isM3u8 = streamUrl.contains(".m3u8")
        val isMp4 = streamUrl.contains(".mp4") || streamUrl.contains("cdn.dsplayer") || streamUrl.contains("/video/")

        if (isM3u8 || isMp4) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $serverName",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = refererUrl
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        return false
    }

    private suspend fun parseDsPlayer(
        playerUrl: String,
        serverLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val response = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = response.text
            val doc = response.document

            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:")) {
                    if (addStreamLink(src, playerUrl, serverLabel, callback)) found = true
                }
            }

            val patterns = listOf(
                Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""["'](https?://cdn\.dsplayer\.site[^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""source\s*:\s*["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(html)) {
                    val candidate = match.groupValues[1]
                    if (addStreamLink(candidate, playerUrl, serverLabel, callback)) {
                        found = true
                    }
                }
            }
        } catch (_: Exception) {}
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

        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                fixUrl(src)
            } else null
        }

        for (embedUrl in iframes) {
            val searchSlug = Regex("""[?&]search=([^&]+)""").find(embedUrl)?.groupValues?.get(1)

            if (!searchSlug.isNullOrEmpty()) {
                val dsplayerVariants = listOf(
                    "https://dsplayer.site/embed2/video.php?slug=$searchSlug",
                    "https://dsplayer.site/embed/video.php?slug=$searchSlug",
                    "https://dsplayer.site/video.php?slug=$searchSlug"
                )
                for ((idx, dsUrl) in dsplayerVariants.withIndex()) {
                    if (parseDsPlayer(dsUrl, "DSPlayer ${idx + 1}", callback)) {
                        success = true
                    }
                }
            }

            try {
                val sroDoc = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                val sroHtml = sroDoc.html()
                val serverCandidates = mutableSetOf<String>()

                for (elem in sroDoc.select("iframe, a, button, div.server, li.server, [data-src], [data-url], [data-embed]")) {
                    val urlCandidate = elem.attr("src").ifEmpty {
                        elem.attr("data-src").ifEmpty {
                            elem.attr("data-url").ifEmpty {
                                elem.attr("data-embed").ifEmpty {
                                    elem.attr("href")
                                }
                            }
                        }
                    }
                    if (urlCandidate.isNotEmpty() && !urlCandidate.startsWith("#") && !urlCandidate.startsWith("javascript")) {
                        serverCandidates.add(fixUrl(urlCandidate))
                    }
                }

                val scriptUrls = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/[^\s"'<>\\]+""").findAll(sroHtml)
                for (match in scriptUrls) {
                    val raw = match.value.replace("\\/", "/")
                    if (!raw.endsWith(".js") && !raw.endsWith(".css") && !raw.endsWith(".png") && !raw.endsWith(".jpg") && !raw.contains("google")) {
                        serverCandidates.add(raw)
                    }
                }

                var serverCounter = 1
                for (candUrl in serverCandidates) {
                    try {
                        if (loadExtractor(candUrl, embedUrl, subtitleCallback, callback)) {
                            success = true
                        }
                    } catch (_: Exception) {}

                    if (candUrl.contains("dsplayer.site")) {
                        if (parseDsPlayer(candUrl, "Server $serverCounter", callback)) {
                            success = true
                            serverCounter++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return success
    }
}
