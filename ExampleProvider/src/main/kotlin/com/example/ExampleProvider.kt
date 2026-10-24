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
        emittedFileKeys: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        forcedQuality: Qualities = Qualities.P1080
    ): Boolean {
        val streamUrl = fixUrl(rawUrl.replace("\\/", "/").trim())
        if (streamUrl.isEmpty() || streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") || streamUrl.endsWith(".js") || streamUrl.endsWith(".css")) {
            return false
        }

        if (!emittedStreams.add(streamUrl)) {
            return false
        }

        val fileName = streamUrl.substringBefore("?").substringAfterLast("/")
        val baseName = fileName.substringBeforeLast(".")
        if (baseName.length > 3 && baseName !in listOf("video", "master", "index", "playlist", "hls")) {
            val key = "$serverName:$baseName"
            if (!emittedFileKeys.add(key)) {
                return false
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var success = false
        val emittedStreams = mutableSetOf<String>()
        val emittedFileKeys = mutableSetOf<String>()
        val visitedPages = mutableSetOf<String>()

        val document = app.get(data, headers = headers).document
        val pagesToScan = mutableSetOf<String>()

        document.select("iframe").forEach {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically")) {
                pagesToScan.add(fixUrl(src))
            }
        }

        document.select("[data-src], [data-url], [data-embed]").forEach { elem ->
            val u = elem.attr("data-src").ifEmpty { elem.attr("data-url").ifEmpty { elem.attr("data-embed") } }
            if (u.contains("embed") || u.contains("php") || u.contains("dsplayer")) {
                pagesToScan.add(fixUrl(u))
            }
        }

        for (embedUrl in pagesToScan.toList()) {
            val searchSlug = Regex("""[?&]search=([^&]+)""").find(embedUrl)?.groupValues?.get(1)
            val cleanSlug = searchSlug?.removePrefix("rs-")?.removePrefix("glo-")

            val candidateSlugs = listOfNotNull(
                cleanSlug,
                searchSlug,
                cleanSlug?.let { "glo-$it" },
                cleanSlug?.let { "rs-$it" }
            ).distinct()

            for (slug in candidateSlugs) {
                pagesToScan.add("https://dsplayer.site/watch.php?v=$slug.mp4")
                pagesToScan.add("https://dsplayer.site/watch.php?v=$slug")
                pagesToScan.add("https://dsplayer.site/embed2/video.php?slug=$slug")
                pagesToScan.add("https://dsplayer.site/video.php?slug=$slug")
            }

            if (embedUrl.contains(Regex("""sro\d*\.php"""))) {
                for (sNum in 1..5) {
                    pagesToScan.add(embedUrl.replace(Regex("""sro\d*\.php"""), "sro$sNum.php"))
                }
            } else if (embedUrl.contains("srofilm.php")) {
                for (sNum in 1..5) {
                    pagesToScan.add(embedUrl.replace("srofilm.php", "sro$sNum.php"))
                    pagesToScan.add(embedUrl.replace("srofilm.php", "srofilm$sNum.php"))
                }
            }
        }

        val queue = pagesToScan.toMutableList()

        while (queue.isNotEmpty()) {
            val currentUrl = queue.removeAt(0)
            if (!visitedPages.add(currentUrl)) continue

            try {
                val response = app.get(currentUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val html = response.text
                val doc = response.document

                // 1. Direkte Videotags
                for (v in doc.select("video source, video")) {
                    val src = v.attr("src").ifEmpty { v.attr("data-src") }
                    if (src.isNotEmpty() && !src.startsWith("blob:")) {
                        val label = when {
                            src.contains("yustream") -> "Server EXYU"
                            src.contains("abyss") -> "Server ABYS"
                            src.contains("callistanise") || currentUrl.contains("callistanise") -> "Vidhide"
                            currentUrl.contains("dsplayer") -> "DSServer"
                            else -> "Server"
                        }
                        val quality = if (src.contains("abyss")) Qualities.P480 else Qualities.P1080
                        if (addStreamLink(src, currentUrl, label, emittedStreams, emittedFileKeys, callback, quality)) {
                            success = true
                        }
                    }
                }

                // 2. Links und Script-Attribute
                val candLinks = mutableSetOf<String>()

                doc.select("iframe, a, button, div, li, [onclick], [data-url], [data-src], [data-embed], [data-link]").forEach { el ->
                    val attrUrl = el.attr("src").ifEmpty {
                        el.attr("data-src").ifEmpty {
                            el.attr("data-url").ifEmpty {
                                el.attr("data-embed").ifEmpty {
                                    el.attr("data-link").ifEmpty {
                                        el.attr("href")
                                    }
                                }
                            }
                        }
                    }
                    if (attrUrl.isNotEmpty() && !attrUrl.startsWith("#") && !attrUrl.startsWith("javascript")) {
                        candLinks.add(fixUrl(attrUrl))
                    }

                    val oc = el.attr("onclick")
                    if (oc.isNotEmpty()) {
                        Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(oc).forEach { m ->
                            candLinks.add(fixUrl(m.value))
                        }
                    }
                }

                Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}[^\s"'<>\\]+""").findAll(html).forEach { m ->
                    candLinks.add(m.value.replace("\\/", "/"))
                }

                for (candUrl in candLinks) {
                    if (candUrl.endsWith(".js") || candUrl.endsWith(".css") || candUrl.endsWith(".png") ||
                        candUrl.endsWith(".jpg") || candUrl.endsWith(".svg") || candUrl.contains("googletag") ||
                        candUrl.contains("managertechnically") || candUrl.contains("al5sm.com")
                    ) continue

                    // 1. SERVER EXYU (yustream.org Direct Stream)
                    if (candUrl.contains("yustream.org")) {
                        if (addStreamLink(candUrl, currentUrl, "Server EXYU", emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                            success = true
                        }
                        continue
                    }

                    // 2. SERVER VIDHIDE (callistanise.com / vidhide mirrors)
                    if (candUrl.contains("callistanise") || candUrl.contains("vidhide")) {
                        val vidhideId = Regex("""[a-zA-Z0-9]{8,}""").find(candUrl.substringAfterLast("/"))?.value
                        if (vidhideId != null) {
                            try {
                                if (loadExtractor("https://vidhidepro.com/embed/$vidhideId", currentUrl, subtitleCallback, callback)) {
                                    success = true
                                }
                            } catch (_: Exception) {}
                        }
                        try {
                            val vidRes = app.get(candUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to currentUrl)).text
                            val directM3u8 = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(vidRes)?.groupValues?.get(1)
                            if (directM3u8 != null) {
                                if (addStreamLink(directM3u8, candUrl, "Vidhide", emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                                    success = true
                                }
                            } else if (vidRes.contains("eval(function(p,a,c,k,e,")) {
                                val unpacked = getAndUnpack(vidRes)
                                val unpackedM3u8 = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                                if (unpackedM3u8 != null) {
                                    if (addStreamLink(unpackedM3u8, candUrl, "Vidhide", emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                                        success = true
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        continue
                    }

                    // 3. SERVER ABYS (abyssplayer.com / play.abyssplayer.com)
                    if (candUrl.contains("abyssplayer") || candUrl.contains("abys")) {
                        if (candUrl.contains(".mp4") || candUrl.contains(".m3u8")) {
                            if (addStreamLink(candUrl, currentUrl, "Server ABYS", emittedStreams, emittedFileKeys, callback, Qualities.P480)) {
                                success = true
                            }
                        } else {
                            try {
                                val abysRes = app.get(candUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to currentUrl))
                                val abysHtml = abysRes.text
                                val abysDoc = abysRes.document

                                for (v in abysDoc.select("video source, video")) {
                                    val src = v.attr("src").ifEmpty { v.attr("data-src") }
                                    if (src.isNotEmpty() && !src.startsWith("blob:")) {
                                        if (addStreamLink(src, candUrl, "Server ABYS", emittedStreams, emittedFileKeys, callback, Qualities.P480)) {
                                            success = true
                                        }
                                    }
                                }

                                Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").findAll(abysHtml).forEach { m ->
                                    if (addStreamLink(m.groupValues[1], candUrl, "Server ABYS", emittedStreams, emittedFileKeys, callback, Qualities.P480)) {
                                        success = true
                                    }
                                }

                                if (abysHtml.contains("eval(function(p,a,c,k,e,")) {
                                    val unpacked = getAndUnpack(abysHtml)
                                    Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").findAll(unpacked).forEach { m ->
                                        if (addStreamLink(m.groupValues[1], candUrl, "Server ABYS", emittedStreams, emittedFileKeys, callback, Qualities.P480)) {
                                            success = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        continue
                    }

                    // 4. SERVER FILEMOON (n1mwq.org / Byse / Filemoon Mirror)
                    if (candUrl.contains("n1mwq") || candUrl.contains("byse") || candUrl.contains("/zxfv5/")) {
                        val filemoonId = Regex("""[a-zA-Z0-9]{8,}""").find(candUrl.substringAfterLast("/"))?.value
                        if (filemoonId != null) {
                            try {
                                if (loadExtractor("https://filemoon.sx/e/$filemoonId", currentUrl, subtitleCallback, callback)) {
                                    success = true
                                }
                            } catch (_: Exception) {}
                        }
                        try {
                            val n1Text = app.get(candUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to currentUrl)).text
                            val directStream = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(n1Text)?.groupValues?.get(1)
                            if (directStream != null) {
                                if (addStreamLink(directStream, candUrl, "Filemoon", emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                                    success = true
                                }
                            } else {
                                val unpacked = getAndUnpack(n1Text)
                                val unpackedStream = Regex("""["'](https?://[^"']*\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                                if (unpackedStream != null) {
                                    if (addStreamLink(unpackedStream, candUrl, "Filemoon", emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                                        success = true
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        continue
                    }

                    // 5. DSServer (Direkte MP4 / M3U8 Streams)
                    if (candUrl.contains(".mp4") || candUrl.contains(".m3u8")) {
                        val label = when {
                            candUrl.contains("dsplayer") || currentUrl.contains("dsplayer") -> "DSServer"
                            else -> "Server"
                        }
                        if (addStreamLink(candUrl, currentUrl, label, emittedStreams, emittedFileKeys, callback, Qualities.P1080)) {
                            success = true
                        }
                        continue
                    }

                    // 6. Standard-Hoster (StreamTape etc.)
                    val isHoster = listOf(
                        "streamtape", "filemoon", "dood", "ds2play",
                        "mixdrop", "voe.sx", "ok.ru", "upstream", "supervideo", "dropload"
                    ).any { candUrl.contains(it) }

                    if (isHoster) {
                        try {
                            if (loadExtractor(candUrl, currentUrl, subtitleCallback, callback)) {
                                success = true
                            }
                        } catch (_: Exception) {}
                        continue
                    }

                    if (candUrl.contains("dsplayer.site") && !visitedPages.contains(candUrl)) {
                        queue.add(candUrl)
                    }
                }
            } catch (_: Exception) {}
        }

        return success
    }
}
