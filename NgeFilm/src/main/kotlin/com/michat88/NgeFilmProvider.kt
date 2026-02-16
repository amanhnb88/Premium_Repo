package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class NgefilmProvider : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Logika Home Page (Sesuai Tahap 1 Mata Tuhan)
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Film Terbaru",
        "$mainUrl/genre/live-streaming/page/" to "Live Streaming",
        "$mainUrl/country/korea/page/" to "Drama Korea",
        "$mainUrl/country/indonesia/page/" to "Indonesia",
        "$mainUrl/country/usa/page/" to "Barat (USA)",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("article.item-list").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrl(this.select("img").attr("src"))
        val quality = this.select(".gmr-quality-item").text().trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)
        }
    }

    // Logika Search (Sesuai Tahap 2 Mata Tuhan)
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document
        return document.select("article.item-list").mapNotNull {
            it.toSearchResult()
        }
    }

    // Logika Detail (Sesuai Tahap 3 Mata Tuhan)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("div.gmr-movie-view img")?.attr("src") 
            ?: document.selectFirst(".content-thumbnail img")?.attr("src")
        
        val description = document.select("div.entry-content p").text().trim()
        val year = document.select("span:contains(Tahun Rilis) a").text().toIntOrNull()
        val rating = document.select("div.gmr-rating-item").text().trim().replace(",", ".").toDoubleOrNull()
        val tags = document.select("span:contains(Genre) a").map { it.text() }
        val trailer = document.select("a.gmr-trailer-popup").attr("href")

        val episodes = ArrayList<Episode>()
        val episodeElements = document.select("div.gmr-list-series a")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { 
                val epTitle = it.text()
                val epHref = it.attr("href")
                val epNum = Regex("Episode\\s+(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating.toRatingInt()
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.rating = rating.toRatingInt()
                addTrailer(trailer)
            }
        }
    }

    // 🔥 LOGIKA LOAD LINKS (IMPLEMENTASI MATA TUHAN V7) 🔥
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Ambil semua elemen IFRAME
        val iframes = document.select("iframe")
        
        iframes.forEach { iframe ->
            // 2. Cek Lazy Load (Prioritas Utama Mata Tuhan)
            var src = iframe.attr("data-litespeed-src")
            if (src.isEmpty()) {
                src = iframe.attr("data-src") // Cek variasi lain
            }
            if (src.isEmpty()) {
                src = iframe.attr("src") // Fallback ke src biasa
            }

            // Bersihkan URL
            src = fixUrl(src)

            // 3. Filter Sampah (Sesuai Logika V7)
            val isJunk = src.contains("googletagmanager") || 
                         src.contains("facebook.com") || 
                         src.contains("twitter.com") || 
                         src.contains("youtube.com") ||
                         src.contains("about:blank")

            // 4. Jika Bersih, Eksekusi!
            if (!isJunk && src.isNotEmpty()) {
                // Khusus untuk RPMLIVE yang ditemukan Mata Tuhan
                if (src.contains("rpmlive")) {
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                } else {
                    // Load extractor umum lainnya
                    loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
