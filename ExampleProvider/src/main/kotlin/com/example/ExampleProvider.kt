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

    private fun extractPoster(document: Document): String? {
        val ogImage = document.selectFirst("meta[property='og:image'], meta[name='twitter:image']")
            ?.attr("content")?.ifEmpty { null }
            ?: document.selectFirst("link[rel='image_src']")?.attr("href")?.ifEmpty { null }

        if (!ogImage.isNullOrEmpty() && !ogImage.contains("logo") && !ogImage.contains("clickonserver")) {
            return fixUrlNull(ogImage)
        }

        val imgElem = document.selectFirst(
            ".single-poster img, .poster img, .poster-img, .movie-poster img, " +
            "img[src*='/slika'], img[data-src*='/slika'], img[src*='/poster'], img[data-src*='/poster'], " +
            ".single-cover img, .film-poster img, .entry-poster img, img.slika, img.poster"
        ) ?: document.selectFirst(".single-content img, .content img, article img")

        val imgUrl = imgElem?.let { img ->
            img.attr("data-src").ifEmpty { null }
                ?: img.attr("data-lazy-src").ifEmpty { null }
                ?: img.attr("data-original").ifEmpty { null }
                ?: img.attr("src").ifEmpty { null }
        }

        if (!imgUrl.isNullOrEmpty() && !imgUrl.contains("logo") && !imgUrl.contains("clickonserver")) {
            return fixUrlNull(imgUrl)
        }

        val bgElem = document.selectFirst("[style*='background-image'], [style*='background:url'], [style*='background: url']")
        if (bgElem != null) {
            val style = bgElem.attr("style")
            val bgUrl = Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(style)?.groupValues?.get(1)
            if (!bgUrl.isNullOrEmpty() && !bgUrl.contains("logo") && !bgUrl.contains("clickonserver")) {
                return fixUrlNull(bgUrl)
            }
        }

        return null
    }

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
        val posterUrl = extractPoster(document)
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

    private suspend fun addDsStreamLink(
        streamUrl: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixed = fixUrl(streamUrl.trim())
        if (fixed.isEmpty() || fixed.contains(".php") || fixed.contains("/watch") || fixed.contains("/embed") ||
            fixed.endsWith(".jpg") || fixed.endsWith(".png") || fixed.endsWith(".js")
        ) return false

        if (!emittedStreams.add(fixed)) return false

        val clean = fixed.substringBefore("?")
        val isM3u8 = clean.endsWith(".m3u8") || fixed.contains(".m3u8")
        val isMp4 = clean.endsWith(".mp4") || fixed.contains("/video/") || fixed.contains("cdn.dsplayer")

        if (isM3u8 || isMp4) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - DSServer (1080p)",
                    url = fixed,
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

    private suspend fun checkAndAddDsPlayer(
        playerUrl: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = res.text
            val doc = res.document

            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:") && !src.contains(".php") && !src.contains("/watch")) {
                    val fullUrl = if (src.startsWith("//")) "https:$src"
                    else if (src.startsWith("/")) "https://cdn.dsplayer.site$src"
                    else src

                    if (addDsStreamLink(fullUrl, emittedStreams, callback)) return true
                }
            }

            val unp = try {
                if (html.contains("eval(function(p,a,c,k,e,")) getAndUnpack(html).replace("\\/", "/") else html.replace("\\/", "/")
            } catch (_: Exception) {
                html.replace("\\/", "/")
            }

            val patterns = listOf(
                Regex("""["'](https?://cdn\.dsplayer\.site/[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*(?:dsplayer)[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""(?:file|source|src)\s*:\s*["']([^"']+)["']""")
            )

            for (p in patterns) {
                for (m in p.findAll(unp)) {
                    val raw = m.groupValues[1].trim()
                    if (raw.isNotEmpty() && !raw.contains(".php") && !raw.contains("/watch") && !raw.contains("/embed")) {
                        val fullUrl = if (raw.startsWith("//")) "https:$raw"
                        else if (raw.startsWith("/")) "https://cdn.dsplayer.site$raw"
                        else raw

                        if (addDsStreamLink(fullUrl, emittedStreams, callback)) return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractByseFilemoon(
        url: String,
        referer: String,
        emittedStreams: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()
        val id = Regex("""/([a-zA-Z0-9_-]{6,})""").find(cleanUrl.substringAfterLast("/"))?.groupValues?.get(1)
            ?: cleanUrl.substringAfterLast("/").substringBefore("?")

        if (id.isNotEmpty()) {
            val filemoonVariants = listOf(
                "https://filemoon.sx/e/$id",
                "https://filemoon.to/e/$id",
                "https://filemoon.in/e/$id"
            )
            for (fmUrl in filemoonVariants) {
                try {
                    if (loadExtractor(fmUrl, referer, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
        }

        try {
            val res = app.get(cleanUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer))
            val text = res.text
            val unp = try {
                if (text.contains("eval(function(p,a,c,k,e,")) getAndUnpack(text).replace("\\/", "/") else text.replace("\\/", "/")
            } catch (_: Exception) {
                text.replace("\\/", "/")
            }

            for (m in Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").findAll(unp)) {
                val direct = m.groupValues[1].trim()
                if (!direct.contains(".php") && !direct.contains("/watch") && !direct.contains("/embed")) {
                    val isM3u8 = direct.contains(".m3u8")
                    if (emittedStreams.add(direct)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - Server Byse (1080p)",
                                url = direct,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = cleanUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }
            }
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

        val frameUrls = mutableSetOf<String>()

        mainDoc.select("iframe").forEach { ifr ->
            val src = ifr.attr("src").ifEmpty {
                ifr.attr("data-src").ifEmpty { ifr.attr("data-lazy-src").ifEmpty { ifr.attr("data-url") } }
            }
            if (src.isNotEmpty() && !src.startsWith("about:") && !src.contains("googletag") && !src.contains("managertechnically")) {
                frameUrls.add(fixUrl(src))
            }
        }

        mainDoc.select("[data-src], [data-url], [data-embed]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty { elem.attr("data-url").ifEmpty { elem.attr("data-embed") } }
            if (u.contains(".php") || u.contains("dsplayer") || u.contains("streamtape") || u.contains("n1mwq")) {
                frameUrls.add(fixUrl(u))
            }
        }

        Regex("""["']([^"']*(?:srofilm|sro|crtaci|embed)\.php[^"']*)["']""").findAll(mainHtml).forEach { m ->
            frameUrls.add(fixUrl(m.groupValues[1]))
        }

        val allServerPages = mutableSetOf<String>()
        allServerPages.addAll(frameUrls)

        for (u in frameUrls) {
            if (u.contains(Regex("""sro\d*\.php"""))) {
                for (sNum in 1..5) {
                    allServerPages.add(u.replace(Regex("""sro\d*\.php"""), "sro$sNum.php"))
                }
            } else if (u.contains("srofilm.php")) {
                for (sNum in 1..5) {
                    allServerPages.add(u.replace("srofilm.php", "sro$sNum.php"))
                    allServerPages.add(u.replace("srofilm.php", "srofilm$sNum.php"))
                }
            }
        }

        // 1. DSServer über Slugs versuchen
        val candidateSlugs = mutableSetOf<String>()
        val pathSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
        if (pathSlug.isNotEmpty() && pathSlug != "film" && pathSlug != "serija") {
            candidateSlugs.add(pathSlug)
            candidateSlugs.add(pathSlug.removePrefix("rs-").removePrefix("glo-"))
        }

        val allTextToScan = mutableListOf(data, mainHtml)
        allTextToScan.addAll(allServerPages)

        for (t in allTextToScan) {
            Regex("""[?&](?:search|v|slug|film|id)=([^&"'\s<>\\]+)""").findAll(t).forEach { m ->
                val s = m.groupValues[1].removeSuffix(".mp4").trim()
                if (s.isNotEmpty() && s.length > 2) {
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
                val dsplayerVariants = listOf(
                    "https://dsplayer.site/watch.php?v=$s.mp4",
                    "https://dsplayer.site/watch.php?v=$s",
                    "https://dsplayer.site/embed2/video.php?slug=$s",
                    "https://dsplayer.site/video.php?slug=$s"
                )
                for (dsUrl in dsplayerVariants) {
                    if (visitedUrls.add(dsUrl)) {
                        if (checkAndAddDsPlayer(dsUrl, emittedStreams, callback)) {
                            dsFound = true
                            break
                        }
                    }
                }
            }
        }

        // 2. Server-Pages nach DSServer, StreamTape und Byse (n1mwq.org) durchsuchen
        for (pageUrl in allServerPages) {
            if (!visitedUrls.add(pageUrl)) continue
            try {
                val pageRes = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val pageHtml = pageRes.text
                val unescaped = if (pageHtml.contains("eval(function(p,a,c,k,e,")) getAndUnpack(pageHtml).replace("\\/", "/") else pageHtml.replace("\\/", "/")

                // DSServer Links
                if (!dsFound) {
                    Regex("""https?://(?:www\.)?dsplayer\.site/[^\s"'<>\\]+""").findAll(unescaped).forEach { m ->
                        val dsUrl = m.value.trim()
                        if (visitedUrls.add(dsUrl)) {
                            if (checkAndAddDsPlayer(dsUrl, emittedStreams, callback)) {
                                dsFound = true
                            }
                        }
                    }
                }

                // Byse / Filemoon (n1mwq.org)
                Regex("""https?://(?:www\.)?n1mwq\.org/[^\s"'<>\\]+""").findAll(unescaped).forEach { m ->
                    val byseUrl = m.value.trim()
                    if (visitedUrls.add(byseUrl)) {
                        extractByseFilemoon(byseUrl, pageUrl, emittedStreams, subtitleCallback, callback)
                    }
                }

                // StreamTape
                Regex("""https?://(?:www\.)?streamtape\.[a-z]+/[^\s"'<>\\]+""").findAll(unescaped).forEach { m ->
                    val stUrl = m.value.trim()
                    if (visitedUrls.add(stUrl)) {
                        try {
                            loadExtractor(stUrl, pageUrl, subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback auf Hauptseite
        Regex("""https?://(?:www\.)?n1mwq\.org/[^\s"'<>\\]+""").findAll(mainHtml.replace("\\/", "/")).forEach { m ->
            val byseUrl = m.value.trim()
            if (visitedUrls.add(byseUrl)) {
                extractByseFilemoon(byseUrl, data, emittedStreams, subtitleCallback, callback)
            }
        }

        Regex("""https?://(?:www\.)?streamtape\.[a-z]+/[^\s"'<>\\]+""").findAll(mainHtml.replace("\\/", "/")).forEach { m ->
            val stUrl = m.value.trim()
            if (visitedUrls.add(stUrl)) {
                try {
                    loadExtractor(stUrl, data, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return emittedStreams.isNotEmpty()
    }
}
