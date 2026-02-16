package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty { this.attr("src") }
        return url.replace(Regex("-\\d+x\\d+"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList("Upload Terbaru", home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst(".content-thumbnail img")?.getImageAttr()
        val quality = this.selectFirst(".gmr-quality-item a")?.text() ?: "HD"
        val ratingText = this.selectFirst(".gmr-rating-item")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        }
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) ActorData(Actor(name, null)) else null
        }

        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")

        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }
        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epText = element.text()
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")
                if (epUrl.isNotEmpty()) newEpisode(epUrl) { this.name = epName; this.episode = epNum } else null
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        }
        if (trailerUrl != null) response.trailers.add(TrailerData(trailerUrl, null, false))
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. SCAN IFRAME UTAMA
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("data-litespeed-src")
            if (src.isEmpty()) src = iframe.attr("data-src")
            if (src.isEmpty()) src = iframe.attr("src")
            
            val fixedSrc = fixUrl(src)
            if (isValidLink(fixedSrc)) {
                if (fixedSrc.contains("rpmlive.online")) {
                    resolveRpmlive(fixedSrc, data, subtitleCallback, callback)
                } else {
                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                }
            }
        }

        // 2. SCAN LINK DOWNLOAD
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        // 3. SCAN SERVER LAIN (AJAX)
        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")
            ?: document.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("?p=")

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val serverTabs = document.select(".muvipro-player-tabs li")
            
            serverTabs.forEach { tab ->
                val nume = tab.attr("data-nume")
                if (nume.isNotEmpty()) {
                    try {
                        val jsonResponse = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to "player-option-$nume",
                                "post_id" to postId
                            ),
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                        ).text

                        val ajaxDoc = Jsoup.parse(jsonResponse)
                        ajaxDoc.select("iframe").forEach { iframe ->
                            var src = iframe.attr("data-litespeed-src")
                            if (src.isEmpty()) src = iframe.attr("src")
                            
                            val fixedSrc = fixUrl(src)
                            if (isValidLink(fixedSrc)) {
                                if (fixedSrc.contains("rpmlive.online")) {
                                    resolveRpmlive(fixedSrc, data, subtitleCallback, callback)
                                } else {
                                    loadExtractor(fixedSrc, data, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        return true
    }

    // --- FUNGSI FIXED: MENGGUNAKAN NEWEXTRACTORLINK ---
    private suspend fun resolveRpmlive(
        url: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(
                url, 
                headers = mapOf("Referer" to referer, "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            ).text

            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
            val m3u8Match = m3u8Regex.find(response)

            if (m3u8Match != null) {
                val videoUrl = m3u8Match.groupValues[1]
                
                // MENGGUNAKAN BUILDER TERBARU (MENGHINDARI ERROR DEPRECATED)
                callback.invoke(
                    newExtractorLink(
                        source = "Rpmlive",
                        name = "Ngefilm21 (VIP)",
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://playerngefilm21.rpmlive.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isValidLink(url: String): Boolean {
        return url.isNotBlank() && 
               !url.contains("about:blank") && 
               !url.contains("youtube.com") && 
               !url.contains("googletagmanager") &&
               !url.contains("facebook.com")
    }
}
