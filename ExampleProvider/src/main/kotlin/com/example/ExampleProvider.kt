package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class ExYuTeka : MainAPI() {
    override var mainUrl = "https://exyuteka.net"
    override var name = "ExYuTeka"
    override val hasMainPage = true
    override var lang = "sr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 1. Suche abfragen
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/pretraga.php?q=$query"
        val document = app.get(searchUrl).document

        return document.select("a.card").mapNotNull { card ->
            val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(card.attr("href"))
            val poster = fixUrlNull(card.selectFirst(".card-poster img")?.attr("src"))
            val year = card.selectFirst(".card-meta span")?.text()?.toIntOrNull()

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // 2. Filmseite laden
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.single-title")?.text()?.trim() ?: "Film"
        val poster = fixUrlNull(document.selectFirst(".single-poster img")?.attr("src"))
        val plot = document.selectFirst("p.single-desc")?.text()?.trim()

        val year = document.select(".single-meta .meta-tag")
            .firstOrNull { it.text().contains("20") }
            ?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // 3. MP4-Stream laden
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val movieDoc = app.get(data).document
        val embedPath = movieDoc.selectFirst("iframe#toChange")?.attr("src")
            ?: movieDoc.selectFirst(".embed-responsive iframe")?.attr("src")
            ?: return false

        val embedUrl = fixUrl(embedPath)
        val playerDoc = app.get(embedUrl, referer = data).document

        val rawVideoUrl = playerDoc.selectFirst("video#player")?.attr("data-src")
            ?: playerDoc.selectFirst("video[data-src]")?.attr("data-src")
            ?: return false

        val videoUrl = rawVideoUrl.replace("&amp;", "&")

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "$name HD",
                url = videoUrl,
                referer = embedUrl,
                quality = Qualities.P1080.value,
                isM3u8 = false
            )
        )

        return true
    }
}
