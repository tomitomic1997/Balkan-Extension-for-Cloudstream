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
                ?: img.attr("srcset").ifEmpty { null }?.substringBefore(" ")
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
        var posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifEmpty { null }
                ?: img?.attr("data-lazy-src")?.ifEmpty { null }
                ?: img?.attr("data-original")?.ifEmpty { null }
                ?: img?.attr("src")?.ifEmpty { null }
        )

        if (posterUrl == null) {
            val bgStyle = element.selectFirst("[style*='background']")?.attr("style")
                ?: targetLink.selectFirst("[style*='background']")?.attr("style")
            val bgMatch = bgStyle?.let { Regex("""url\(['"]?([^'"\)]+)['"]?\)""").find(it)?.groupValues?.get(1) }
            if (!bgMatch.isNullOrEmpty()) {
                posterUrl = fixUrlNull(bgMatch)
            }
        }

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
                                ?: epImg?.attr("data-lazy-src")?.ifEmpty { null }
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
                            ?: epImg?.attr("data-lazy-src")?.ifEmpty { null }
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

        // Blockiert Webseiten, fehlerhafte Container und Script-Dateien (verhindert Fehler 3003)
        if (streamUrl.isEmpty() || streamUrl.contains(".php") || streamUrl.contains("/watch") ||
            streamUrl.contains("/embed") || streamUrl.endsWith(".jpg") || streamUrl.endsWith(".png") ||
            streamUrl.endsWith(".js") || streamUrl.endsWith(".css") || streamUrl.endsWith(".svg")
        ) return false

        if (!emittedStreams.add(streamUrl)) return false

        val cleanUrl = streamUrl.substringBefore("?")
        val isM3u8 = cleanUrl.endsWith(".m3u8") || streamUrl.contains(".m3u8")
        val isMp4 = cleanUrl.endsWith(".mp4") || streamUrl.contains("/video/") ||
                streamUrl.contains("cdn.dsplayer.site") ||
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

    private suspend fun extractDsPlayer(
        playerUrl: String,
        referer: String,
        emittedStreams: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val doc = res.document
            val html = res.text

            // 1. Direkte Videotags im DSPlayer
            for (v in doc.select("video source, video")) {
                val src = v.attr("src").ifEmpty { v.attr("data-src") }
                if (src.isNotEmpty() && !src.startsWith("blob:") && !src.contains(".php") && !src.contains("/watch")) {
                    if (addStreamLink(src, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                        return true
                    }
                }
            }

            // 2. JavaScript-Packer entpacken
            val unp = if (html.contains("eval(function(p,a,c,k,e,")) {
                getAndUnpack(html).replace("\\/", "/")
            } else {
                html.replace("\\/", "/")
            }

            val patterns = listOf(
                Regex("""["'](https?://cdn\.dsplayer\.site/video/[^"']*\.mp4[^"']*)["']"""),
                Regex("""["'](https?://[^"']*(?:cdn\.dsplayer\.site)[^"']*\.mp4[^"']*)["']"""),
                Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
                Regex("""src\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
            )

            for (p in patterns) {
                val candidate = p.find(unp)?.groupValues?.get(1)?.trim()
                if (!candidate.isNullOrEmpty() && !candidate.contains(".php") && !candidate.contains("/watch") && !candidate.contains("/embed")) {
                    if (addStreamLink(candidate, playerUrl, "DSServer", emittedStreams, callback, Qualities.P1080)) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun handleExtractedServerUrl(
        url: String,
        referer: String,
        emittedStreams: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()

        if (cleanUrl.contains("abyss") || cleanUrl.contains("sssrr.org")) return

        if (cleanUrl.contains("yustream.org") && (cleanUrl.endsWith(".mp4") || cleanUrl.contains("/uploads/"))) {
            addStreamLink(cleanUrl, referer, "Server EXYU", emittedStreams, callback, Qualities.P1080)
            return
        }

        if (cleanUrl.contains("callistanise") || cleanUrl.contains("vidhide")) {
            val id = Regex("""/([a-zA-Z0-9_-]{6,})""").find(cleanUrl.substringAfterLast("/"))?.groupValues?.get(1)
                ?: cleanUrl.substringAfterLast("/").substringBefore("?")
            if (id.isNotEmpty()) {
                try {
                    if (loadExtractor("https://vidhidepro.com/embed/$id", referer, subtitleCallback, callback)) return
                } catch (_: Exception) {}
                try {
                    if (loadExtractor("https://vidhide.com/embed/$id", referer, subtitleCallback, callback)) return
                } catch (_: Exception) {}
            }
            return
        }

        if (cleanUrl.contains("n1mwq") || cleanUrl.contains("filemoon") || cleanUrl.contains("byse")) {
            val id = Regex("""/([a-zA-Z0-9_-]{6,})""").find(cleanUrl.substringAfterLast("/"))?.groupValues?.get(1)
                ?: cleanUrl.substringAfterLast("/").substringBefore("?")
            if (id.isNotEmpty()) {
                try {
                    if (loadExtractor("https://filemoon.sx/e/$id", referer, subtitleCallback, callback)) return
                } catch (_: Exception) {}
                try {
                    if (loadExtractor("https://filemoon.to/e/$id", referer, subtitleCallback, callback)) return
                } catch (_: Exception) {}
            }
            return
        }

        if (cleanUrl.contains("streamtape")) {
            try {
                loadExtractor(cleanUrl, referer, subtitleCallback, callback)
            } catch (_: Exception) {}
            return
        }
    }

    private fun parseServerUrlsFromCode(html: String): List<String> {
        val results = mutableListOf<String>()
        val unescaped = if (html.contains("eval(function(p,a,c,k,e,")) {
            getAndUnpack(html).replace("\\/", "/")
        } else {
            html.replace("\\/", "/")
        }

        val patterns = listOf(
            Regex("""src\s*=\s*["'](https?://[^"']+)["']"""),
            Regex("""["'](https?://(?:www\.)?yustream\.org/[^"']+)["']"""),
            Regex("""["'](https?://(?:www\.)?callistanise\.com/[^"']+)["']"""),
            Regex("""["'](https?://(?:www\.)?n1mwq\.org/[^"']+)["']"""),
            Regex("""["'](https?://(?:www\.)?streamtape\.com/[^"']+)["']""")
        )

        for (p in patterns) {
            for (m in p.findAll(unescaped)) {
                val u = m.groupValues[1].trim()
                if (!u.contains("abyss") && !u.contains("sssrr") && !results.contains(u)) {
                    results.add(u)
                }
            }
        }
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emittedStreams = mutableSetOf<String>()
        val visitedUrls = mutableSetOf<String>()
        var dsServerAdded = false

        val mainResponse = app.get(data, headers = headers)
        val mainDoc = mainResponse.document
        val mainHtml = mainResponse.text

        val framesToScan = mutableSetOf<String>()

        mainDoc.select("iframe, [data-src], [data-url], [data-embed]").forEach { elem ->
            val src = elem.attr("src").ifEmpty {
                elem.attr("data-src").ifEmpty {
                    elem.attr("data-url").ifEmpty { elem.attr("data-embed") }
                }
            }
            if (src.isNotEmpty() && !src.contains("googletag") && !src.contains("managertechnically") && !src.contains("al5sm")) {
                val fixed = fixUrl(src)
                framesToScan.add(fixed)

                if (fixed.contains("sro")) {
                    for (i in 1..5) {
                        framesToScan.add(fixed.replace(Regex("""sro[a-zA-Z0-9]*\.php"""), "sro$i.php"))
                        framesToScan.add(fixed.replace(Regex("""sro[a-zA-Z0-9]*\.php"""), "srofilm$i.php"))
                    }
                }
            }
        }

        Regex("""["']([^"']*(?:crtaci|sro\d*|srofilm\d*|embed\d*)\.php[^"']*)["']""").findAll(mainHtml).forEach { m ->
            framesToScan.add(fixUrl(m.groupValues[1]))
        }

        mainDoc.select("[onclick]").forEach { el ->
            Regex("""(?:https?:)?//[^\s'"\(\)]+""").findAll(el.attr("onclick")).forEach { m ->
                framesToScan.add(fixUrl(m.value))
            }
        }

        // 1. SLUGS IDENTIFIZIEREN (Filme & Serien)
        val candidateSlugs = mutableSetOf<String>()

        // Aus dem Pfad der aktuellen Seite (z. B. /film/drazen-2024 -> drazen-2024)
        val urlSlug = data.removeSuffix("/").substringAfterLast("/").substringBefore(".").substringBefore("?")
        if (urlSlug.isNotEmpty() && urlSlug != "film" && urlSlug != "serija") {
            candidateSlugs.add(urlSlug)
            candidateSlugs.add(urlSlug.removePrefix("rs-").removePrefix("glo-"))
        }

        // Aus allen Query-Parametern der Seite und der Frames
        val allSources = mutableListOf(mainHtml, data)
        allSources.addAll(framesToScan)

        for (source in allSources) {
            Regex("""[?&](?:search|v|slug|film|id)=([^&"'\s<>\\]+)""").findAll(source).forEach { m ->
                val raw = m.groupValues[1].removeSuffix(".mp4").trim()
                if (raw.isNotEmpty() && raw.length > 2) {
                    candidateSlugs.add(raw)
                    candidateSlugs.add(raw.removePrefix("rs-").removePrefix("glo-"))
                }
            }
        }

        // 2. DSSERVER LADEN
        for (slug in candidateSlugs) {
            if (dsServerAdded) break
            val clean = slug.removePrefix("rs-").removePrefix("glo-")
            val slugVariants = listOfNotNull(clean, "rs-$clean", "glo-$clean", slug).distinct()

            for (s in slugVariants) {
                if (dsServerAdded) break
                val dsVariants = listOf(
                    "https://dsplayer.site/embed2/video.php?slug=$s",
                    "https://dsplayer.site/video.php?slug=$s",
                    "https://dsplayer.site/watch.php?v=$s.mp4",
                    "https://dsplayer.site/watch.php?v=$s"
                )
                for (dsUrl in dsVariants) {
                    if (dsServerAdded) break
                    if (!visitedUrls.add(dsUrl)) continue
                    try {
                        if (extractDsPlayer(dsUrl, data, emittedStreams, callback)) {
                            dsServerAdded = true
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 3. DIREKTE DSPLAYER-URLS PRÜFEN
        for (fUrl in framesToScan) {
            if (dsServerAdded) break
            if (fUrl.contains("dsplayer.site") && visitedUrls.add(fUrl)) {
                try {
                    if (extractDsPlayer(fUrl, data, emittedStreams, callback)) {
                        dsServerAdded = true
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. WEITERE HOSTER-FRAMES ABSUCHEN
        for (frameUrl in framesToScan) {
            if (!visitedUrls.add(frameUrl)) continue
            try {
                val res = app.get(frameUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data))
                val frameHtml = res.text
                val frameDoc = res.document

                for (eu in parseServerUrlsFromCode(frameHtml)) {
                    handleExtractedServerUrl(eu, frameUrl, emittedStreams, subtitleCallback, callback)
                }

                for (childIfr in frameDoc.select("iframe")) {
                    val childSrc = childIfr.attr("src").ifEmpty { childIfr.attr("data-src") }
                    if (childSrc.isNotEmpty()) {
                        val fixedChild = fixUrl(childSrc)
                        if (visitedUrls.add(fixedChild)) {
                            try {
                                val childRes = app.get(fixedChild, headers = mapOf("User-Agent" to userAgent, "Referer" to frameUrl))
                                for (ceu in parseServerUrlsFromCode(childRes.text)) {
                                    handleExtractedServerUrl(ceu, fixedChild, emittedStreams, subtitleCallback, callback)
                                }
                                if (!dsServerAdded && fixedChild.contains("dsplayer.site")) {
                                    if (extractDsPlayer(fixedChild, frameUrl, emittedStreams, callback)) {
                                        dsServerAdded = true
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 5. DIREKTE LINKS AUF DER HAUPTSEITE
        for (eu in parseServerUrlsFromCode(mainHtml)) {
            handleExtractedServerUrl(eu, data, emittedStreams, subtitleCallback, callback)
        }

        return emittedStreams.isNotEmpty()
    }
}
