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
        var targetUrl = url
        var document = app.get(targetUrl, headers = headers).document

        // Falls direkt eine einzelne Folge aufgerufen wird: zur Hauptserie wechseln
        if (targetUrl.contains("/ep/")) {
            val parentSeriesHref = document.selectFirst("a[href*='/serija/'], a[href*='/gledaj-online/']")?.attr("href")
            if (!parentSeriesHref.isNullOrEmpty()) {
                targetUrl = fixUrl(parentSeriesHref)
                document = app.get(targetUrl, headers = headers).document
            }
        }

        val title = document.selectFirst(".single-title, h1, .title")?.text()?.trim() ?: "ExYuTeka"
        val posterUrl = fixUrlNull(
            document.selectFirst(".single-poster img, .poster img, img[src*='/slika']")?.attr("data-src")
                ?: document.selectFirst(".single-poster img, .poster img, img[src*='/slika']")?.attr("src")
        )
        val plot = document.selectFirst(".single-desc, .description, p")?.text()?.trim()
        val year = document.selectFirst(".single-meta")?.text()?.let {
            Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull()
        }

        val isMovie = targetUrl.contains("/film/")

        if (!isMovie) {
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

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = "Epizoda $episodeNum"
                                this.season = seasonNum
                                this.episode = episodeNum
                            }
                        )
                    }
                }
            } else {
                // Fallback für Folgenseiten oder alternative Layouts
                val epLinks = document.select("a.episode-item, a.ep-row, a[href*='/ep/']")
                    .filter { it.attr("href").isNotEmpty() }

                epLinks.forEachIndexed { index, epElem ->
                    val epHref = fixUrl(epElem.attr("href"))
                    val epText = epElem.text().trim()
                    val seMatch = Regex("""[sS](\d+)[eE](\d+)""").find(epHref) ?: Regex("""[sS](\d+)[eE](\d+)""").find(epText)
                    val sNum = seMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val eNum = seMatch?.groupValues?.get(2)?.toIntOrNull()
                        ?: Regex("""[eE]pizoda\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Epizoda $eNum"
                            this.season = sNum
                            this.episode = eNum
                        }
                    )
                }
            }

            if (episodes.isNotEmpty()) {
                val cleanEpisodes = episodes.distinctBy { it.data }
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, cleanEpisodes) {
                    this.posterUrl = posterUrl
                    this.plot = plot
                    this.year = year
                }
            }
        }

        return newMovieLoadResponse(title, targetUrl, TvType.Movie, targetUrl) {
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

        // Alle Iframes auf der Seite einsammeln
        val iframes = document.select("iframe").mapNotNull {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                fixUrl(src)
            } else null
        }.toMutableList()

        // Auch direkte Buttons oder Server-Links berücksichtigen
        document.select("[data-src], [data-url], [data-embed]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty { elem.attr("data-url").ifEmpty { elem.attr("data-embed") } }
            if (u.contains("embed") || u.contains("php")) {
                iframes.add(fixUrl(u))
            }
        }

        for (embedUrl in iframes.distinct()) {
            val searchSlug = Regex("""[?&]search=([^&]+)""").find(embedUrl)?.groupValues?.get(1)
            val cleanSlug = searchSlug?.removePrefix("rs-")?.removePrefix("glo-")

            // DSPlayer-Varianten mit beiden Slug-Formaten testen
            val candidateSlugs = listOfNotNull(searchSlug, cleanSlug).distinct()
            for (slug in candidateSlugs) {
                val dsplayerVariants = listOf(
                    "https://dsplayer.site/embed2/video.php?slug=$slug",
                    "https://dsplayer.site/embed/video.php?slug=$slug",
                    "https://dsplayer.site/video.php?slug=$slug"
                )
                for ((idx, dsUrl) in dsplayerVariants.withIndex()) {
                    if (parseDsPlayer(dsUrl, "DSPlayer ${idx + 1}", callback)) {
                        success = true
                    }
                }
            }

            // Server 1 bis 5 (sro1.php bis sro5.php) durchgehen
            val serverUrls = mutableListOf(embedUrl)
            if (embedUrl.contains(Regex("""sro\d*\.php"""))) {
                for (sNum in 1..5) {
                    serverUrls.add(embedUrl.replace(Regex("""sro\d*\.php"""), "sro$sNum.php"))
                }
            }

            for ((sIndex, sUrl) in serverUrls.distinct().withIndex()) {
                try {
                    val sroDoc = app.get(sUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document
                    val sroHtml = sroDoc.html()

                    // 1. Direkt im Embed-Dokument nach Streams suchen
                    for (v in sroDoc.select("video source, video")) {
                        val src = v.attr("src").ifEmpty { v.attr("data-src") }
                        if (src.isNotEmpty() && !src.startsWith("blob:")) {
                            if (addStreamLink(src, sUrl, "Server ${sIndex + 1}", callback)) success = true
                        }
                    }

                    // 2. Eingebettete Hoster-Iframes in der Player-Seite auslesen
                    for (nestedIframe in sroDoc.select("iframe")) {
                        val nestedSrc = nestedIframe.attr("src").ifEmpty { nestedIframe.attr("data-src") }
                        if (nestedSrc.isNotEmpty()) {
                            val fullNested = fixUrl(nestedSrc)
                            try {
                                if (loadExtractor(fullNested, sUrl, subtitleCallback, callback)) {
                                    success = true
                                }
                            } catch (_: Exception) {}

                            if (fullNested.contains("dsplayer.site")) {
                                if (parseDsPlayer(fullNested, "Server ${sIndex + 1}", callback)) success = true
                            }
                        }
                    }

                    // 3. URLs in Skripten prüfen
                    val scriptMatches = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/[^\s"'<>\\]+""").findAll(sroHtml)
                    for (m in scriptMatches) {
                        val foundUrl = m.value.replace("\\/", "/")
                        if (foundUrl.contains("dsplayer.site")) {
                            if (parseDsPlayer(foundUrl, "Server ${sIndex + 1}", callback)) success = true
                        }
                        if (foundUrl.contains(".mp4") || foundUrl.contains(".m3u8")) {
                            if (addStreamLink(foundUrl, sUrl, "Server ${sIndex + 1}", callback)) success = true
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return success
    }
}
