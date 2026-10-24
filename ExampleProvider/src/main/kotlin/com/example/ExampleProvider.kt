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
        forcedQuality: Qualities = Qualities.P1080
    ): Boolean {
        val streamUrl = fixUrl(rawUrl.replace("\\/", "/").trim())
        if (streamUrl.isEmpty() || streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") ||
            streamUrl.endsWith(".js") || streamUrl.endsWith(".css") || streamUrl.endsWith(".svg")
        ) {
            return false
        }

        if (!emittedStreams.add(streamUrl)) {
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
                    this.quality = forcedQuality.value
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
        try {
            val response = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = response.text
            val doc = response.document

            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:")) {
                    if (addStreamLink(src, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) return true
                }
            }

            val unescapedHtml = html.replace("\\/", "/")
            val patterns = listOf(
                Regex("""["'](https?://[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']"""),
                Regex("""["'](https?://cdn\.dsplayer\.site[^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']+)["']"""),
                Regex("""source\s*:\s*["']([^"']+)["']""")
            )

            for (pattern in patterns) {
                for (match in pattern.findAll(unescapedHtml)) {
                    val candidate = match.groupValues[1]
                    if (addStreamLink(candidate, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                        return true
                    }
                }
            }

            if (html.contains("eval(function(p,a,c,k,e,")) {
                val unpacked = getAndUnpack(html).replace("\\/", "/")
                for (pattern in patterns) {
                    for (match in pattern.findAll(unpacked)) {
                        val candidate = match.groupValues[1]
                        if (addStreamLink(candidate, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun parseVidhide(
        vidUrl: String,
        refererUrl: String,
        emittedStreams: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val id = Regex("""[a-zA-Z0-9]{8,}""").find(vidUrl.substringAfterLast("/"))?.value
            if (id != null) {
                try {
                    if (loadExtractor("https://vidhidepro.com/embed/$id", refererUrl, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
            val res = app.get(vidUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl)).text
            val textToSearch = if (res.contains("eval(function(p,a,c,k,e,")) {
                getAndUnpack(res).replace("\\/", "/")
            } else {
                res.replace("\\/", "/")
            }
            val m3u8Match = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
            if (m3u8Match != null) {
                return addStreamLink(m3u8Match, vidUrl, "Vidhide", emittedStreams, callback, Qualities.P1080)
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun parseFilemoon(
        filemoonUrl: String,
        refererUrl: String,
        emittedStreams: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val id = Regex("""[a-zA-Z0-9]{8,}""").find(filemoonUrl.substringAfterLast("/"))?.value
            if (id != null) {
                try {
                    if (loadExtractor("https://filemoon.sx/e/$id", refererUrl, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }
            val res = app.get(filemoonUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl)).text
            val textToSearch = if (res.contains("eval(function(p,a,c,k,e,")) {
                getAndUnpack(res).replace("\\/", "/")
            } else {
                res.replace("\\/", "/")
            }
            val streamMatch = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
            if (streamMatch != null) {
                return addStreamLink(streamMatch, filemoonUrl, "Filemoon", emittedStreams, callback, Qualities.P1080)
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun parseAbyss(
        abyssUrl: String,
        refererUrl: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(abyssUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl))
            val doc = res.document
            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:")) {
                    if (addStreamLink(src, abyssUrl, "Server ABYS", emittedStreams, callback, Qualities.P480)) return true
                }
            }
            val text = res.text
            val textToSearch = if (text.contains("eval(function(p,a,c,k,e,")) {
                getAndUnpack(text).replace("\\/", "/")
            } else {
                text.replace("\\/", "/")
            }
            val streamMatch = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(textToSearch)?.groupValues?.get(1)
            if (streamMatch != null) {
                return addStreamLink(streamMatch, abyssUrl, "Server ABYS", emittedStreams, callback, Qualities.P480)
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
        var success = false
        val emittedStreams = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()

        val doc = app.get(data, headers = headers).document
        val rawHtml = doc.html()

        val embedUrls = mutableSetOf<String>()

        doc.select("iframe").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                embedUrls.add(fixUrl(src))
            }
        }

        doc.select("[data-src], [data-url], [data-embed], [data-link]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty {
                elem.attr("data-url").ifEmpty {
                    elem.attr("data-embed").ifEmpty { elem.attr("data-link") }
                }
            }
            if (u.contains("embed") || u.contains("php") || u.contains("player") || u.contains("stream")) {
                embedUrls.add(fixUrl(u))
            }
        }

        doc.select("button[onclick], div[onclick], a[onclick], li[onclick]").forEach { el ->
            val oc = el.attr("onclick")
            Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(oc).forEach { m ->
                embedUrls.add(fixUrl(m.value))
            }
        }

        Regex("""(?:https?:)?//[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(rawHtml.replace("\\/", "/")).forEach { m ->
            val u = fixUrl(m.value)
            if (u.contains("sro") || u.contains("yustream") || u.contains("callistanise") ||
                u.contains("n1mwq") || u.contains("abyss") || u.contains("dsplayer") || u.contains("streamtape")
            ) {
                embedUrls.add(u)
            }
        }

        val sroUrls = mutableSetOf<String>()
        for (url in embedUrls) {
            if (url.contains(Regex("""sro\d*\.php"""))) {
                for (i in 1..5) {
                    sroUrls.add(url.replace(Regex("""sro\d*\.php"""), "sro$i.php"))
                }
            } else if (url.contains("srofilm.php")) {
                for (i in 1..5) {
                    sroUrls.add(url.replace("srofilm.php", "sro$i.php"))
                    sroUrls.add(url.replace("srofilm.php", "srofilm$i.php"))
                }
            }
        }
        embedUrls.addAll(sroUrls)

        val allCandidateLinks = mutableSetOf<String>()
        allCandidateLinks.addAll(embedUrls)

        for (sroUrl in sroUrls) {
            if (!visitedUrls.add(sroUrl)) continue
            try {
                val sroRes = app.get(sroUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val sroDoc = sroRes.document
                val sroHtml = sroRes.text.replace("\\/", "/")

                sroDoc.select("iframe, a, button, div, [data-src], [data-url], [data-embed]").forEach { el ->
                    val u = el.attr("src").ifEmpty {
                        el.attr("data-src").ifEmpty {
                            el.attr("data-url").ifEmpty { el.attr("data-embed").ifEmpty { el.attr("href") } }
                        }
                    }
                    if (u.isNotEmpty() && !u.startsWith("#") && !u.startsWith("javascript")) {
                        allCandidateLinks.add(fixUrl(u))
                    }
                }

                sroDoc.select("[onclick]").forEach { el ->
                    Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(el.attr("onclick")).forEach { m ->
                        allCandidateLinks.add(fixUrl(m.value))
                    }
                }

                Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(sroHtml).forEach { m ->
                    val u = m.value
                    if (u.contains("yustream") || u.contains("callistanise") || u.contains("n1mwq") ||
                        u.contains("abyss") || u.contains("dsplayer") || u.contains("streamtape") ||
                        u.contains(".mp4") || u.contains(".m3u8")
                    ) {
                        allCandidateLinks.add(u)
                    }
                }
            } catch (_: Exception) {}
        }

        var dsFound = false

        for (u in embedUrls) {
            val searchSlug = Regex("""[?&]search=([^&]+)""").find(u)?.groupValues?.get(1)
            val cleanSlug = searchSlug?.removePrefix("rs-")?.removePrefix("glo-")
            val candidateSlugs = listOfNotNull(cleanSlug, searchSlug).distinct()
            for (slug in candidateSlugs) {
                if (dsFound) break
                for (variant in listOf(
                    "https://dsplayer.site/watch.php?v=$slug.mp4",
                    "https://dsplayer.site/watch.php?v=$slug"
                )) {
                    if (visitedUrls.add(variant)) {
                        if (parseDsPlayer(variant, emittedStreams, callback)) {
                            success = true
                            dsFound = true
                            break
                        }
                    }
                }
            }
        }

        for (candUrl in allCandidateLinks) {
            if (!visitedUrls.add(candUrl)) continue

            if (candUrl.endsWith(".js") || candUrl.endsWith(".css") || candUrl.endsWith(".png") ||
                candUrl.endsWith(".jpg") || candUrl.endsWith(".svg") || candUrl.contains("googletag") ||
                candUrl.contains("managertechnically") || candUrl.contains("al5sm.com")
            ) continue

            // 1. SERVER EXYU (yustream.org)
            if (candUrl.contains("yustream.org")) {
                if (addStreamLink(candUrl, data, "Server EXYU", emittedStreams, callback, Qualities.P1080)) {
                    success = true
                }
                continue
            }

            // 2. VIDHIDE (callistanise.com / vidhide)
            if (candUrl.contains("callistanise") || candUrl.contains("vidhide")) {
                if (parseVidhide(candUrl, data, emittedStreams, subtitleCallback, callback)) {
                    success = true
                }
                continue
            }

            // 3. FILEMOON (n1mwq.org / byse / filemoon)
            if (candUrl.contains("n1mwq") || candUrl.contains("byse") || candUrl.contains("filemoon")) {
                if (parseFilemoon(candUrl, data, emittedStreams, subtitleCallback, callback)) {
                    success = true
                }
                continue
            }

            // 4. SERVER ABYS (abyssplayer / abys)
            if (candUrl.contains("abyssplayer") || candUrl.contains("abys")) {
                if (parseAbyss(candUrl, data, emittedStreams, callback)) {
                    success = true
                }
                continue
            }

            // 5. STREAMTAPE
            if (candUrl.contains("streamtape")) {
                try {
                    if (loadExtractor(candUrl, data, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (_: Exception) {}
                continue
            }

            // 6. DSPLAYER (DSServer)
            if (candUrl.contains("dsplayer.site")) {
                if (!dsFound) {
                    if (parseDsPlayer(candUrl, emittedStreams, callback)) {
                        success = true
                        dsFound = true
                    }
                }
                continue
            }

            // Generic MP4 / M3U8
            if (candUrl.contains(".mp4") || candUrl.contains(".m3u8")) {
                if (addStreamLink(candUrl, data, "Server", emittedStreams, callback, Qualities.P1080)) {
                    success = true
                }
                continue
            }

            val isHoster = listOf(
                "dood", "ds2play", "mixdrop", "voe.sx", "ok.ru", "upstream", "supervideo", "dropload"
            ).any { candUrl.contains(it) }

            if (isHoster) {
                try {
                    if (loadExtractor(candUrl, data, subtitleCallback, callback)) {
                        success = true
                    }
                } catch (_: Exception) {}
            }
        }

        return success
    }
}
