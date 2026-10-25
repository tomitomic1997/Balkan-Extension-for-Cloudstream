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

    private fun addDsStreamLink(
        streamUrl: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = streamUrl.substringBefore("?")
        val isM3u8 = clean.endsWith(".m3u8") || streamUrl.contains(".m3u8")
        val isMp4 = clean.endsWith(".mp4") || streamUrl.contains("/video/") || streamUrl.contains("cdn.dsplayer")

        if ((isM3u8 || isMp4) && emittedStreams.add(streamUrl)) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - DSServer",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://dsplayer.site/"
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
        return false
    }

    private fun findAndAddDsStream(
        html: String,
        doc: Document,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (v in doc.select("video source, video")) {
            val src = v.attr("src").ifEmpty { v.attr("data-src") }
            if (src.isNotEmpty() && !src.startsWith("blob:") && !src.contains(".php") && !src.contains("/watch")) {
                if (addDsStreamLink(fixUrl(src), emittedStreams, callback)) return true
            }
        }

        val unp = if (html.contains("eval(function(p,a,c,k,e,")) {
            getAndUnpack(html).replace("\\/", "/")
        } else {
            html.replace("\\/", "/")
        }

        val patterns = listOf(
            Regex("""(?:file|source|src)\s*:\s*["']([^"']+)["']"""),
            Regex("""["'](https?://cdn\.dsplayer\.site/[^"']+)["']"""),
            Regex("""["'](https?://[^"']*(?:cdn\.dsplayer\.site)[^"']*\.mp4[^"']*)["']"""),
            Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
            Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
            Regex("""["'](/video/[^"']*\.mp4[^"']*)["']"""),
            Regex("""["'](//[^"']*(?:dsplayer)[^"']*\.mp4[^"']*)["']""")
        )

        for (p in patterns) {
            for (m in p.findAll(unp)) {
                val raw = m.groupValues[1].trim()
                if (raw.isNotEmpty() && !raw.contains(".php") && !raw.contains("/watch") && !raw.contains("/embed")) {
                    if (addDsStreamLink(fixUrl(raw), emittedStreams, callback)) return true
                }
            }
        }
        return false
    }

    private suspend fun extractDsPlayer(
        url: String,
        referer: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = fixUrl(url)
        try {
            val res = app.get(targetUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer))
            val html = res.text
            val doc = res.document

            for (iframe in doc.select("iframe")) {
                val ifrSrc = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (ifrSrc.isNotEmpty() && !ifrSrc.startsWith("about:") && !ifrSrc.contains("google")) {
                    val fixedIfr = fixUrl(ifrSrc)
                    if (fixedIfr != targetUrl) {
                        try {
                            val innerRes = app.get(fixedIfr, headers = mapOf("User-Agent" to userAgent, "Referer" to targetUrl))
                            if (findAndAddDsStream(innerRes.text, innerRes.document, emittedStreams, callback)) {
                                return true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            return findAndAddDsStream(html, doc, emittedStreams, callback)
        } catch (_: Exception) {}
        return false
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emittedStreams = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()
        var dsFound = false

        val mainResponse = app.get(data, headers = headers)
        val mainDoc = mainResponse.document
        val mainHtml = mainResponse.text

        val playerPages = mutableSetOf<String>()

        mainDoc.select("iframe").forEach { ifr ->
            var src = ifr.attr("src")
            if (src.isEmpty() || src == "about:blank") {
                src = ifr.attr("data-src").ifEmpty { ifr.attr("data-lazy-src").ifEmpty { ifr.attr("data-url") } }
            }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                playerPages.add(fixUrl(src))
            }
        }

        mainDoc.select("[data-src], [data-url], [data-embed]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty { elem.attr("data-url").ifEmpty { elem.attr("data-embed") } }
            if (u.contains(".php") || u.contains("dsplayer") || u.contains("streamtape")) {
                playerPages.add(fixUrl(u))
            }
        }

        mainDoc.select("[onclick]").forEach { el ->
            Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(el.attr("onclick")).forEach { m ->
                playerPages.add(fixUrl(m.value))
            }
        }

        Regex("""["']([^"']*(?:srofilm|sro|crtaci|embed)\.php[^"']*)["']""").findAll(mainHtml).forEach { m ->
            playerPages.add(fixUrl(m.groupValues[1]))
        }
        Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(mainHtml.replace("\\/", "/")).forEach { m ->
            val u = m.value
            if (u.contains("sro") || u.contains("crtaci") || u.contains("dsplayer") || u.contains("streamtape")) {
                playerPages.add(fixUrl(u))
            }
        }

        // 1. Player-Frames öffnen und switch-case nach DSServer und StreamTape durchsuchen
        for (pUrl in playerPages) {
            if (!visitedUrls.add(pUrl)) continue
            try {
                val pRes = app.get(pUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val pHtml = pRes.text
                val pDoc = pRes.document

                val linksInPage = mutableSetOf<String>()
                val unescaped = if (pHtml.contains("eval(function(p,a,c,k,e,")) getAndUnpack(pHtml).replace("\\/", "/") else pHtml.replace("\\/", "/")

                Regex("""src\s*=\s*["']([^"']+)["']""").findAll(unescaped).forEach { m ->
                    linksInPage.add(m.groupValues[1].trim())
                }
                Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(unescaped).forEach { m ->
                    linksInPage.add(m.value.trim())
                }

                pDoc.select("iframe").forEach { ifr ->
                    val src = ifr.attr("src").ifEmpty { ifr.attr("data-src") }
                    if (src.isNotEmpty() && !src.startsWith("about:") && !src.contains("googletag")) {
                        linksInPage.add(fixUrl(src))
                    }
                }

                for (candUrl in linksInPage) {
                    if (!dsFound && candUrl.contains("dsplayer.site")) {
                        if (extractDsPlayer(candUrl, pUrl, emittedStreams, callback)) {
                            dsFound = true
                        }
                    }

                    if (candUrl.contains("streamtape")) {
                        try {
                            loadExtractor(candUrl, pUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: Slug-Varianten bei Filmen ohne direkte Server-Verlinkung
        if (!dsFound) {
            val candidateSlugs = mutableSetOf<String>()
            val pathSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
            if (pathSlug.isNotEmpty() && pathSlug != "film" && pathSlug != "serija") {
                candidateSlugs.add(pathSlug)
                candidateSlugs.add(pathSlug.removePrefix("rs-").removePrefix("glo-"))
            }

            for (u in playerPages + listOf(data)) {
                Regex("""[?&](?:search|v|slug|film)=([^&"'\s<>\\]+)""").findAll(u).forEach { m ->
                    val s = m.groupValues[1].removeSuffix(".mp4").trim()
                    if (s.isNotEmpty()) {
                        candidateSlugs.add(s)
                        candidateSlugs.add(s.removePrefix("rs-").removePrefix("glo-"))
                    }
                }
            }

            for (slug in candidateSlugs) {
                if (dsFound) break
                val clean = slug.removePrefix("rs-").removePrefix("glo-")
                for (s in listOfNotNull(clean, "rs-$clean", slug).distinct()) {
                    if (dsFound) break
                    val dsVariants = listOf(
                        "https://dsplayer.site/embed2/video.php?slug=$s",
                        "https://dsplayer.site/video.php?slug=$s",
                        "https://dsplayer.site/watch.php?v=$s.mp4",
                        "https://dsplayer.site/watch.php?v=$s"
                    )
                    for (dsUrl in dsVariants) {
                        if (extractDsPlayer(dsUrl, data, emittedStreams, callback)) {
                            dsFound = true
                            break
                        }
                    }
                }
            }
        }

        // 3. Direkte StreamTape-Links auf der Filmseite abfangen
        for (u in playerPages) {
            if (u.contains("streamtape") && visitedUrls.add(u)) {
                try {
                    loadExtractor(u, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return emittedStreams.isNotEmpty()
    }
}
