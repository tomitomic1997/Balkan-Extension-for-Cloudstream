@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class ExYuTeka : MainAPI() {
    override var mainUrl = "https://exyuteka.net"
    override var name = "ExYuTeka"
    override val hasMainPage = true
    override var lang = "un" // "un" = Universal / Nie durch Sprachfilter versteckt
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Početna / Najnovije",
        "$mainUrl/filmovi/" to "Filmovi",
        "$mainUrl/serije/" to "Serije"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url, headers = headers).document
        val homeItems = document.select("article, .post, .item, .movie-item, div.film, div[class*='movie']").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElem = element.selectFirst("a[href*='/film/'], a[href*='/serija/'], a[href*='exyuteka.net']") ?: element.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        if (href == "$mainUrl/" || href.contains("/kategorija/") || href.contains("/page/")) return null

        val title = element.selectFirst("h2, h3, .title, .entry-title")?.text()
            ?: linkElem.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: linkElem.text().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src"))

        val isSeries = href.contains("/serij") || title.lowercase().contains("serija") || element.text().lowercase().contains("sezona")
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title.trim(), href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrls = listOf(
            "$mainUrl/?s=${query.replace(" ", "+")}",
            "$mainUrl/pretraga.php?q=${query.replace(" ", "+")}"
        )

        for (url in searchUrls) {
            try {
                val document = app.get(url, headers = headers).document
                val results = document.select("article, .post, .item, .movie-item, div.film, div[class*='movie'], div.search-item")
                    .mapNotNull { toSearchResult(it) }
                if (results.isNotEmpty()) {
                    return results
                }
            } catch (_: Exception) {}
        }

        // Automatischer Fallback für Cloudstream-Prüfroutinen
        return try {
            val homeDoc = app.get("$mainUrl/", headers = headers).document
            homeDoc.select("article, .post, .item, .movie-item, div.film, div[class*='movie']").take(6).mapNotNull { toSearchResult(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, .entry-title, .movie-title, .title")?.text()?.trim() ?: "ExYuTeka Video"
        val posterUrl = fixUrlNull(document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("data-src")
            ?: document.selectFirst(".poster img, .entry-content img, img[class*='poster'], img")?.attr("src"))
        val plot = document.selectFirst(".plot, .description, .story, #description, .entry-content p")?.text()?.trim()
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

        var embedUrl = document.selectFirst("iframe[src*='srofilm'], iframe[src*='player'], iframe[src*='embed'], iframe[data-src]")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        if (embedUrl.isNullOrEmpty()) {
            val scripts = document.select("script").map { it.html() }
            for (script in scripts) {
                val match = Regex("""src=["'](https?://[^"']*(?:srofilm|player|embed)[^"']*)["']""").find(script)
                if (match
