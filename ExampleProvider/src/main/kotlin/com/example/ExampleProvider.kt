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
                                this.posterUrl = posterUrl
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

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Epizoda $eNum"
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = posterUrl
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
        
        // Blockiert Webseiten, PHP-Skripte und Platzhalter (verhindert Fehler 3003)
        if (streamUrl.isEmpty() || streamUrl.contains(".php") || streamUrl.contains("/watch") ||
            streamUrl.contains("/embed") || streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") ||
            streamUrl.endsWith(".js") || streamUrl.endsWith(".css") || streamUrl.endsWith(".svg")
        ) return false

        if (!emittedStreams.add(streamUrl)) return false

        val cleanUrl = streamUrl.substringBefore("?")
        val isM3u8 = cleanUrl.endsWith(".m3u8") || streamUrl.contains(".m3u8")
        val isMp4 = cleanUrl.endsWith(".mp4") || streamUrl.contains("/video/") ||
                (streamUrl.contains("cdn.dsplayer.site") && !streamUrl.contains(".php")) ||
                streamUrl.contains("yustream.org")

        if (isM3u8 || isMp4) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $serverName",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = refererUrl
                    this.quality = quality.value
                }
            )
            return true
        }
        return false
    }

    private suspend fun scanHtmlForServers(
        html: String,
        refererUrl: String,
        emittedStreams: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = html.replace("\\/", "/")

        // 1. SERVER EXYU: yustream.org (Direkt-MP4 1080p)
        for (m in Regex("""https?://(?:www\.)?yustream\.org/[^\s"'<>\\]+""").findAll(unescaped)) {
            val u = m.value.trim()
            if (u.endsWith(".mp4") || u.contains("/uploads/")) {
                addStreamLink(u, refererUrl, "Server EXYU", emittedStreams, callback, Qualities.P1080)
            }
        }

        // 2. SERVER FILEMOON / BYSE: n1mwq.org
        for (m in Regex("""https?://(?:n1mwq\.org|filemoon\.[a-z]+)/[^\s"'<>\\]+""").findAll(unescaped)) {
            val u = m.value.trim()
            if (u.contains("n1mwq.org") || u.contains("byse")) {
                try {
                    val res = app.get(u, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl)).text
                    val unpacked = if (res.contains("eval(function(p,a,c,k,e,")) getAndUnpack(res).replace("\\/", "/") else res.replace("\\/", "/")
                    val match = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                        ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                    if (match != null) {
                        addStreamLink(match, u, "Filemoon", emittedStreams, callback, Qualities.P1080)
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    loadExtractor(u, refererUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        // 3. SERVER VIDHIDE: callistanise.com
        for (m in Regex("""https?://(?:callistanise\.com|vidhide\.[a-z]+|vidhidepro\.[a-z]+)/[^\s"'<>\\]+""").findAll(unescaped)) {
            val u = m.value.trim()
            if (u.contains("callistanise.com")) {
                try {
                    val res = app.get(u, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl)).text
                    val unpacked = if (res.contains("eval(function(p,a,c,k,e,")) getAndUnpack(res).replace("\\/", "/") else res.replace("\\/", "/")
                    val match = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                        ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unescaped)?.groupValues?.get(1)
                    if (match != null) {
                        addStreamLink(match, u, "Vidhide", emittedStreams, callback, Qualities.P1080)
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    loadExtractor(u, refererUrl, subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        // 4. SERVER ABYS: play.abyssplayer.com
        for (m in Regex("""https?://(?:play\.)?abyssplayer\.com/[^\s"'<>\\]+""").findAll(unescaped)) {
            val abyssUrl = m.value.trim()
            try {
                val res = app.get(abyssUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl))
                var found = false
                for (v in res.document.select("video source, video")) {
                    val src = v.attr("src").ifEmpty { v.attr("data-src") }
                    if (src.isNotEmpty() && !src.startsWith("blob:")) {
                        if (addStreamLink(src, abyssUrl, "Server ABYS", emittedStreams, callback, Qualities.P480)) {
                            found = true
                            break
                        }
                    }
                }
                if (!found) {
                    val unescapedDoc = if (res.text.contains("eval(function(p,a,c,k,e,")) getAndUnpack(res.text).replace("\\/", "/") else res.text.replace("\\/", "/")
                    val match = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(unescapedDoc)?.groupValues?.get(1)
                        ?: Regex("""file\s*:\s*["']([^"']+)["']""").find(unescapedDoc)?.groupValues?.get(1)
                    if (match != null) {
                        addStreamLink(match, abyssUrl, "Server ABYS", emittedStreams, callback, Qualities.P480)
                    }
                }
            } catch (_: Exception) {}
        }

        // 5. SERVER STREAM / STREAMTAPE
        for (m in Regex("""https?://(?:streamtape\.com|streamtape\.[a-z]+)/[^\s"'<>\\]+""").findAll(unescaped)) {
            val u = m.value.trim()
            try {
                loadExtractor(u, refererUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
    }

    private suspend fun extractSingleDsPlayerStream(
        playerUrl: String,
        referer: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to referer))
            val doc = res.document
            val html = res.text

            // 1. Genau den ersten funktionierenden Video-Tag nehmen und sofort beenden
            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:") && !src.contains(".php") && !src.contains("/watch")) {
                    if (addStreamLink(src, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                        return true
                    }
                }
            }

            // 2. Falls kein direkter Tag existiert: Regex im Script auswerten
            val unescaped = if (html.contains("eval(function(p,a,c,k,e,")) {
                getAndUnpack(html).replace("\\/", "/")
            } else {
                html.replace("\\/", "/")
            }

            val patterns = listOf(
                Regex("""["'](https?://cdn\.dsplayer\.site/video/[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
            )

            for (p in patterns) {
                for (m in p.findAll(unescaped)) {
                    val candidate = m.groupValues[1]
                    if (!candidate.contains(".php") && !candidate.contains("/watch") && !candidate.contains("/embed")) {
                        if (addStreamLink(candidate, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                            return true
                        }
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

        // 1. Filmseite nach Server-Links durchsuchen
        scanHtmlForServers(mainHtml, data, emittedStreams, subtitleCallback, callback)

        // 2. Alle Player-Frames und Buttons einsammeln
        val embedUrls = mutableSetOf<String>()

        mainDoc.select("iframe, [data-src], [data-url], [data-embed]").forEach { elem ->
            val src = elem.attr("src").ifEmpty {
                elem.attr("data-src").ifEmpty {
                    elem.attr("data-url").ifEmpty { elem.attr("data-embed") }
                }
            }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically") && !src.contains("al5sm")) {
                embedUrls.add(fixUrl(src))
            }
        }

        mainDoc.select("[onclick]").forEach { el ->
            Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(el.attr("onclick")).forEach { m ->
                embedUrls.add(fixUrl(m.value))
            }
        }

        // Server-Tabs (sro1..5 und srofilm1..5) ableiten
        val sroCandidates = mutableSetOf<String>()
        for (u in embedUrls) {
            if (u.contains("sro")) {
                for (i in 1..5) {
                    sroCandidates.add(u.replace(Regex("""sro[a-zA-Z0-9]*\.php"""), "sro$i.php"))
                    sroCandidates.add(u.replace(Regex("""sro[a-zA-Z0-9]*\.php"""), "srofilm$i.php"))
                }
            }
        }
        embedUrls.addAll(sroCandidates)

        // Slugs für DSPlayer generieren
        val dsUrls = mutableSetOf<String>()
        for (u in embedUrls) {
            val slug = Regex("""[?&]search=([^&]+)""").find(u)?.groupValues?.get(1)
            val cleanSlug = slug?.removePrefix("rs-")?.removePrefix("glo-")
            listOfNotNull(cleanSlug, slug).distinct().forEach { s ->
                dsUrls.add("https://dsplayer.site/watch.php?v=$s.mp4")
                dsUrls.add("https://dsplayer.site/watch.php?v=$s")
                dsUrls.add("https://dsplayer.site/embed2/video.php?slug=$s")
            }
        }

        // 3. DSPlayer abrufen: Exakt 1 funktionierenden DSServer-Stream holen & Frame nach den anderen Hoster-Buttons durchsuchen
        for (dsUrl in dsUrls) {
            if (!visitedUrls.add(dsUrl)) continue
            try {
                val res = app.get(dsUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val html = res.text

                // Liest EXYU, Filemoon, ABYS, Vidhide direkt aus dem DSPlayer-Menü aus
                scanHtmlForServers(html, dsUrl, emittedStreams, subtitleCallback, callback)

                if (!dsFound && extractSingleDsPlayerStream(dsUrl, data, emittedStreams, callback)) {
                    dsFound = true
                }
            } catch (_: Exception) {}
        }

        // 4. Server-Seiten (sro1.php bis sro5.php) nach restlichen Hostern durchsuchen
        for (pageUrl in embedUrls) {
            if (!visitedUrls.add(pageUrl)) continue
            try {
                val res = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val html = res.text
                val doc = res.document

                scanHtmlForServers(html, pageUrl, emittedStreams, subtitleCallback, callback)

                for (ifr in doc.select("iframe")) {
                    val ifrSrc = ifr.attr("src").ifEmpty { ifr.attr("data-src") }
                    if (ifrSrc.isNotEmpty()) {
                        scanHtmlForServers(fixUrl(ifrSrc), pageUrl, emittedStreams, subtitleCallback, callback)
                    }
                }

                if (!dsFound && pageUrl.contains("dsplayer.site")) {
                    if (extractSingleDsPlayerStream(pageUrl, data, emittedStreams, callback)) {
                        dsFound = true
                    }
                }
            } catch (_: Exception) {}
        }

        return emittedStreams.isNotEmpty()
    }
}
