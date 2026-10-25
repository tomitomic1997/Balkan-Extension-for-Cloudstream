@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    private fun parseCardElement(element: Element): SearchResponse? {
        val targetLink = if (element.tagName() == "a") {
            element
        } else {
            element.selectFirst("a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/'], a[href*='/ep/'], a") ?: return null
        }

        val href = fixUrlNull(targetLink.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.endsWith(".php") || href.endsWith("#")) return null

        val img = element.selectFirst("img") ?: targetLink.selectFirst("img") ?: element.parent()?.selectFirst("img")
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

        val isSeries = href.contains("/serija/") ||
                href.contains("/gledaj-online/") ||
                href.contains("/ep/") ||
                element.selectFirst(".serija-badge") != null ||
                element.hasClass("ep-row")

        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()
        val cards = document.select(
            "a.card, div.card, a.ep-row, div.ep-row, a.episode-item, .card-grid > a, .card-grid > div, .ep-list > a, " +
            "a[href*='/film/'], a[href*='/serija/'], a[href*='/gledaj-online/'], a[href*='/ep/']"
        )

        for (elem in cards) {
            val item = parseCardElement(elem) ?: continue
            if (seenUrls.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val reqData = request.data

        if (reqData.startsWith("$mainUrl/#trending-")) {
            val document = app.get(mainUrl, headers = headers).document
            val results = mutableListOf<SearchResponse>()
            val isSeriesTrending = reqData.endsWith("serije")

            val targetHeader = if (isSeriesTrending) "Trending Serije" else "Trending Filmovi"
            val section = document.select("section.section").firstOrNull { sec ->
                sec.selectFirst(".section-title")?.text()?.contains(targetHeader, ignoreCase = true) == true
            }

            if (section != null) {
                for (link in section.select("a.card, div.card")) {
                    parseCardElement(link)?.let { results.add(it) }
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

        if (targetUrl.contains("/ep/")) {
            val parentSeriesHref = document.selectFirst("a[href*='/serija/'], a[href*='/gledaj-online/']")?.attr("href")
            if (!parentSeriesHref.isNullOrEmpty()) {
                targetUrl = fixUrl(parentSeriesHref)
                document = app.get(targetUrl, headers = headers).document
            }
        }

        val title = document.selectFirst(".single-title, h1, .title")?.text()?.trim() ?: "ExYuTeka"
        val posterImg = document.selectFirst(".single-poster img, .poster img, img[src*='/slika'], .movie-poster img, .entry-poster img")
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

                        val epImg = epElem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-src")?.ifEmpty { null }
                                ?: epImg?.attr("src")?.ifEmpty { null }
                        ) ?: posterUrl

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = "Epizoda $episodeNum"
                                this.season = seasonNum
                                this.episode = episodeNum
                                this.posterUrl = epPoster
                            }
                        )
                    }
                }
            } else {
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

                    val epImg = epElem.selectFirst("img")
                    val epPoster = fixUrlNull(
                        epImg?.attr("data-src")?.ifEmpty { null }
                            ?: epImg?.attr("src")?.ifEmpty { null }
                    ) ?: posterUrl

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Epizoda $eNum"
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = epPoster
                        }
                    )
                }
            }

            if (episodes.isNotEmpty()) {
                val cleanEpisodes = episodes.distinctBy { it.data }
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, cleanEpisodes) {
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

    private suspend fun addStreamLink(
        rawUrl: String,
        refererUrl: String,
        serverName: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        quality: Qualities = Qualities.P1080
    ): Boolean {
        val streamUrl = fixUrl(rawUrl.replace("\\/", "/").trim())
        if (streamUrl.isEmpty() || streamUrl.contains(".php") || streamUrl.contains("/watch") ||
            streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") || streamUrl.endsWith(".js") ||
            streamUrl.endsWith(".css") || streamUrl.endsWith(".svg")
        ) return false

        if (!emittedStreams.add(streamUrl)) return false

        val cleanUrl = streamUrl.substringBefore("?")
        val isM3u8 = cleanUrl.endsWith(".m3u8") || streamUrl.contains(".m3u8")
        val isMp4 = cleanUrl.endsWith(".mp4") || streamUrl.contains("/video/") ||
                streamUrl.contains("cdn.dsplayer")

        if (isM3u8 || isMp4) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $serverName",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://dsplayer.site/"
                    this.quality = quality.value
                }
            )
            return true
        }
        return false
    }

    private suspend fun parseDsPlayer(
        playerUrl: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val response = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = response.text
            val doc = response.document

            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:") && !src.contains(".php")) {
                    if (addStreamLink(src, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                        found = true
                    }
                }
            }

            val unp = try {
                if (html.contains("eval(function(p,a,c,k,e,")) getAndUnpack(html).replace("\\/", "/") else html.replace("\\/", "/")
            } catch (_: Exception) {
                html.replace("\\/", "/")
            }

            val patterns = listOf(
                Regex("""["'](https?://cdn\.dsplayer\.site/video/[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*(?:cdn\.dsplayer\.site)[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""(?:file|source|src)\s*:\s*["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(unp)) {
                    val candidate = match.groupValues[1].trim()
                    if (candidate.isNotEmpty() && !candidate.contains(".php") && !candidate.contains("/watch") && !candidate.contains("/embed")) {
                        if (addStreamLink(candidate, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                            found = true
                            break
                        }
                    }
                }
                if (found) break
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
        val emittedStreams = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()

        val document = app.get(data, headers = headers).document
        val embedUrls = mutableSetOf<String>()

        document.select("iframe").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                embedUrls.add(fixUrl(src))
            }
        }

        document.select("[data-src], [data-url], [data-embed]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty { elem.attr("data-url").ifEmpty { elem.attr("data-embed") } }
            if (u.contains("embed") || u.contains("php") || u.contains("dsplayer") || u.contains("streamtape")) {
                embedUrls.add(fixUrl(u))
            }
        }

        val serverUrls = mutableSetOf<String>()
        serverUrls.addAll(embedUrls)

        for (embedUrl in embedUrls) {
            if (embedUrl.contains(Regex("""sro\d*\.php"""))) {
                for (sNum in 1..5) {
                    serverUrls.add(embedUrl.replace(Regex("""sro\d*\.php"""), "sro$sNum.php"))
                }
            } else if (embedUrl.contains("srofilm.php")) {
                for (sNum in 1..5) {
                    serverUrls.add(embedUrl.replace("srofilm.php", "sro$sNum.php"))
                    serverUrls.add(embedUrl.replace("srofilm.php", "srofilm$sNum.php"))
                }
            }
        }

        // 1. DSServer über Slugs
        var dsFound = false
        val candidateSlugs = mutableSetOf<String>()

        val pathSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
        if (pathSlug.isNotEmpty() && pathSlug != "film" && pathSlug != "serija") {
            candidateSlugs.add(pathSlug)
            candidateSlugs.add(pathSlug.removePrefix("rs-").removePrefix("glo-"))
        }

        for (embedUrl in embedUrls) {
            Regex("""[?&](?:search|v|slug|film)=([^&"'\s<>\\]+)""").findAll(embedUrl).forEach { m ->
                val s = m.groupValues[1].removeSuffix(".mp4").trim()
                if (s.isNotEmpty()) {
                    candidateSlugs.add(s)
                    candidateSlugs.add(s.removePrefix("rs-").removePrefix("glo-"))
                }
            }
        }

        for (slug in candidateSlugs) {
            if (dsFound) break
            val cleanSlug = slug.removePrefix("rs-").removePrefix("glo-")
            for (s in listOfNotNull(cleanSlug, "rs-$cleanSlug", slug).distinct()) {
                if (dsFound) break
                val dsplayerVariants = listOf(
                    "https://dsplayer.site/watch.php?v=$s.mp4",
                    "https://dsplayer.site/watch.php?v=$s",
                    "https://dsplayer.site/embed2/video.php?slug=$s",
                    "https://dsplayer.site/video.php?slug=$s"
                )
                for (dsUrl in dsplayerVariants) {
                    if (visitedUrls.add(dsUrl)) {
                        if (parseDsPlayer(dsUrl, emittedStreams, callback)) {
                            dsFound = true
                            break
                        }
                    }
                }
            }
        }

        // 2. Server-Tabs nach DSServer und StreamTape durchsuchen
        for (sUrl in serverUrls) {
            if (!visitedUrls.add(sUrl)) continue
            try {
                val sroResponse = app.get(sUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val sroDoc = sroResponse.document
                val sroHtml = sroResponse.text

                val potentialLinks = mutableSetOf<String>()

                sroDoc.select("iframe, a, button, div, li, [onclick], [data-src], [data-url], [data-embed]").forEach { el ->
                    val src = el.attr("src").ifEmpty {
                        el.attr("data-src").ifEmpty {
                            el.attr("data-url").ifEmpty {
                                el.attr("data-embed").ifEmpty { el.attr("href") }
                            }
                        }
                    }
                    if (src.isNotEmpty() && !src.startsWith("#") && !src.startsWith("javascript")) {
                        potentialLinks.add(fixUrl(src))
                    }

                    val oc = el.attr("onclick")
                    if (oc.isNotEmpty()) {
                        Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(oc).forEach { m ->
                            potentialLinks.add(fixUrl(m.value))
                        }
                    }
                }

                Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(sroHtml.replace("\\/", "/")).forEach { m ->
                    potentialLinks.add(m.value)
                }

                for (candUrl in potentialLinks) {
                    if (candUrl.endsWith(".js") || candUrl.endsWith(".css") || candUrl.endsWith(".png") ||
                        candUrl.endsWith(".jpg") || candUrl.endsWith(".svg") || candUrl.contains("googletag") ||
                        candUrl.contains("managertechnically")
                    ) continue

                    if (!dsFound && candUrl.contains("dsplayer.site") && visitedUrls.add(candUrl)) {
                        if (parseDsPlayer(candUrl, emittedStreams, callback)) {
                            dsFound = true
                        }
                    }

                    if (candUrl.contains("streamtape") && visitedUrls.add(candUrl)) {
                        try {
                            loadExtractor(candUrl, sUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Direkte StreamTape-Links auf der Hauptseite
        for (u in embedUrls) {
            if (u.contains("streamtape") && visitedUrls.add(u)) {
                try {
                    loadExtractor(u, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return emittedStreams.isNotEmpty()
    }
}
