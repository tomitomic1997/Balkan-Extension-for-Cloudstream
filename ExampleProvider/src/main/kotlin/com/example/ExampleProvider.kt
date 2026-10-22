@file:Suppress("DEPRECATION_ERROR", "DEPRECATION")

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ExYuTeka : MainAPI() {
    override var mainUrl = "https://exyuteka.net"
    override var name = "ExYuTeka"
    override val hasMainPage = false
    override var lang = "hr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/pretraga.php?q=$query"
        val document = app.get(url).document

        return document.select("div.movie-item, div.item, a[href*='film.php'], a[href*='serija.php']").mapNotNull { element ->
            val title = element.selectFirst("h3, h2, .title, .name")?.text() 
                ?: element.attr("title").ifEmpty { null } 
                ?: element.text().ifEmpty { null } 
                ?: return@mapNotNull null
            val href = fixUrl(element.attr("href").ifEmpty { element.selectFirst("a")?.attr("href") ?: return@mapNotNull null })
            val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .movie-title, .title")?.text() ?: "ExYuTeka Video"
        val posterUrl = fixUrlNull(document.selectFirst(".poster img, .movie-poster img, img[src*='poster']")?.attr("src"))
        val plot = document.selectFirst(".plot, .description, .story, #description")?.text()
        val year = document.selectFirst(".year, .release-date")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

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
        val document = app.get(data).document

        var embedUrl = document.selectFirst("iframe[src*='srofilm'], iframe[src*='player'], iframe[src*='embed']")?.attr("src")

        if (embedUrl.isNullOrEmpty()) {
            val scripts = document.select("script").map { it.html() }
            for (script in scripts) {
                val match = Regex("""src=["'](https?://[^"']*srofilm[^"']*)["']""").find(script)
                    ?: Regex("""["'](https?://[^"']*player[^"']*)["']""").find(script)
                if (match != null) {
                    embedUrl = match.groupValues[1]
                    break
                }
            }
        }

        val targetUrl = if (!embedUrl.isNullOrEmpty()) fixUrl(embedUrl) else data
        val playerDoc = app.get(targetUrl, referer = data).document

        var videoUrl = playerDoc.selectFirst("video#player")?.attr("data-src")
            ?: playerDoc.selectFirst("video source")?.attr("src")
            ?: playerDoc.selectFirst("video")?.attr("src")

        if (videoUrl.isNullOrEmpty()) {
            val html = playerDoc.html()
            val match = Regex("""https?://cdn\.dsplayer\.site[^\s"'<>]+""").find(html)
                ?: Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)
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
