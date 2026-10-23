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
        "$mainUrl/filmovi.php" to "Filmovi",
        "$mainUrl/serije.php" to "Serije"
    )

    private fun parseMoviesFromDoc(document: Document): List<SearchResponse> {
        val seenUrls = mutableSetOf<String>()
        val results = mutableListOf<SearchResponse>()

        // Sucht alle klickbaren Elemente, die ein Bild (Film-Cover) enthalten oder in einer Karte liegen
        val elements = document.select("a:has(img), div:has(img) > a, article a, [class*='movie'] a, [class*='film'] a, [class*='card'] a")

        for (link in elements) {
            val href = fixUrlNull(link.attr("href")) ?: continue

            // Startseite und reine Menü-Links überspringen
            if (href == mainUrl || href == "$mainUrl/" || href.endsWith("index.php") || href.endsWith("#")) continue
            if (href.contains("login") || href.contains("register") || href.contains("kontakt")) continue

            val card = link.parent() ?: link
            val img = link.selectFirst("img") ?: card.selectFirst("img") ?: continue

            val posterUrl = fixUrlNull(
                img.attr("data-src").ifEmpty {
                    img.attr("data-lazy-src").ifEmpty {
                        img.attr("src")
                    }
                }
            ) ?: continue

            // Website-Logo und kleine Icons ignorieren
            if (posterUrl.contains("logo", ignoreCase = true) || posterUrl.contains("icon", ignoreCase = true)) continue

            // Filmtitel aus allen gängigen Feldern oder aus der URL ableiten
            val rawTitle = link.attr("title").trim().ifEmpty { null }
                ?: img.attr("alt").trim().ifEmpty { null }
                ?: card.selectFirst("h1, h2, h3, h4, h5, .title, .name, [class*='title'], p, span")?.text()?.trim()?.ifEmpty { null }
                ?: link.text().trim().ifEmpty { null }
                ?: href.substringAfterLast("/").substringBefore(".php").substringBefore(".html").replace("-", " ").replace("_", " ").trim()

            if (rawTitle.isEmpty()) continue

            // Menü-Begriffe wie Početna, Filmovi, Serije explizit herausfiltern
            val lowerTitle = rawTitle.lowercase()
            if (lowerTitle in listOf("početna", "pocetna", "filmovi", "serije", "najnovije", "home", "pretraga")) continue

            if (seenUrls.add(href)) {
                val isSeries = href.contains("serij", ignoreCase = true) || lowerTitle.contains("serija") || lowerTitle.contains("sezona")
                val type = if (isSeries) TvType.TvSeries else TvType.Movie

                results.add(
                    newMovieSearchResponse(rawTitle, href, type) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }

        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var document = app.get(request.data, headers = headers).document
        var items = parseMoviesFromDoc(document)

        // Falls die Unterseite ohne .php heißt (z. B. /filmovi/ statt /filmovi.php)
        if (items.isEmpty() && request.data.endsWith(".php")) {
            val altUrl = request.data.removeSuffix(".php") + "/"
            try {
                document = app.get(altUrl, headers = headers).document
                items = parseMoviesFromDoc(document)
            } catch (_: Exception) {}
        }

        // Diagnose-Karte anzeigen, falls die Seite Filme per JavaScript versteckt
        if (items.isEmpty() && request.name == "Najnovije") {
            val debugItem = newMovieSearchResponse("Diagnose-Info (Hier klicken)", "$mainUrl/#debug", TvType.Movie)
            return newHomePageResponse(request.name, listOf(debugItem))
        }

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
                val results = parseMoviesFromDoc(document)
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Exception) {}
        }

        return try {
            val homeDoc = app.get("$mainUrl/", headers = headers).document
            parseMoviesFromDoc(homeDoc).take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Wenn auf die Diagnose-Karte geklickt wird, zeige den HTML-Aufbau in der Beschreibung an
        if (url.endsWith("#debug")) {
            val doc = app.get(mainUrl, headers = headers).document
            val bodySnippet = doc.body()?.html()?.take(1500) ?: "Kein HTML-Body gefunden"
            return newMovieLoadResponse("HTML-Diagnose von ExYuTeka", url, TvType.Movie, url) {
                this.plot = bodySnippet
            }
        }

        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, h2, .entry-title, .movie-title, .title")?.text()?.trim() ?: "ExYuTeka Video"
        val posterUrl = fixUrlNull(
            document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("data-src")
                ?: document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("src")
        )
        val plot = document.selectFirst(".plot, .description, .story, #description, .entry-content p, p")?.text()?.trim()
        val year = document.selectFirst(".year, .release-date, span[class*='year']")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        var embedUrl = document.selectFirst("iframe[src*='srofilm'], iframe[src*='player'], iframe[src*='embed'], iframe[data-src], iframe")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        if (embedUrl.isNullOrEmpty()) {
            val scripts = document.select("script").map { it.html() }
            for (script in scripts) {
                val match = Regex("""src=["'](https?://[^"']*(?:srofilm|player|embed)[^"']*)["']""").find(script)
                if (match != null) {
                    embedUrl = match.groupValues[1]
                    break
                }
            }
        }

        val targetUrl = if (!embedUrl.isNullOrEmpty()) fixUrl(embedUrl) else data
        val playerDoc = app.get(targetUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to data)).document

        var videoUrl = playerDoc.selectFirst("video#player")?.attr("data-src")
            ?: playerDoc.selectFirst("video source")?.attr("src")
            ?: playerDoc.selectFirst("video")?.attr("src")

        if (videoUrl.isNullOrEmpty()) {
            val html = playerDoc.html()
            val match = Regex("""https?://cdn\.dsplayer\.site[^\s"'<>]+""").find(html)
                ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)
                ?: Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)
            if (match != null) {
                videoUrl = match.value
            }
        }

        if (!videoUrl.isNullOrEmpty()) {
            val isM3u8Link = videoUrl.contains(".m3u8")
            val linkType = if (isM3u8Link) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoUrl,
                    type = linkType
                ) {
                    this.referer = data
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        return false
    }
}
