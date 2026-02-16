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

    // Helper untuk mengambil gambar (menangani lazy load)
    private fun Element.getImageAttr(): String? {
        return this.attr("data-src").ifEmpty {
            this.attr("src")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
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
            // Fix: Assign score langsung ke property
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document

        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.page-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-poster img")?.getImageAttr()
        val plot = document.selectFirst(".entry-content p")?.text()?.trim()
        val year = document.selectFirst("a[href*='/year/']")?.text()?.toIntOrNull()
        
        val ratingText = document.selectFirst(".gmr-rating-item")?.text()?.trim()

        // Menentukan tipe (Movie/Series)
        val isSeries = document.select(".gmr-listseries").isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val tags = document.select("a[rel='category tag']").map { it.text() }

        if (isSeries) {
            val episodes = document.select(".gmr-listseries a").mapNotNull {
                val epTitle = it.text()
                val epUrl = it.attr("href")
                val epNum = Regex("Episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                
                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                } else null
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                // PERBAIKAN DI SINI: Ganti addScore() dengan assignment langsung
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                // PERBAIKAN DI SINI: Ganti addScore() dengan assignment langsung
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            
            if (!src.contains("youtube.com") && !src.contains("wp-embedded-content")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        val serverIds = document.select(".gmr-player-nav li a").mapNotNull { 
            it.attr("data-post") to it.attr("data-nume") 
        }
        
        if (serverIds.isNotEmpty()) {
            serverIds.forEach { (postId, num) ->
                val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                val formData = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to "player$num",
                    "post_id" to postId
                )
                
                try {
                    val response = app.post(ajaxUrl, data = formData).text
                    val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                    if (iframeSrc.isNotEmpty()) {
                         loadExtractor(iframeSrc, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }
        }

        return true
    }
}
