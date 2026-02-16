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

    // Helper untuk membersihkan URL gambar
    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty { this.attr("src") }
        return url.replace(Regex("-\\d+x\\d+"), "")
    }

    // --- HALAMAN UTAMA ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList("Upload Terbaru", home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        
        // Prioritas Poster: Data-Src (Lazy) -> Src
        val posterUrl = this.selectFirst(".content-thumbnail img")?.getImageAttr()
        
        val quality = this.selectFirst(".gmr-quality-item a")?.text() ?: "HD"
        val ratingText = this.selectFirst(".gmr-rating-item")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    // --- PENCARIAN ---
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    // --- DETAIL HALAMAN (LOAD) ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        
        // FIX POSTER: Ambil dari Meta OG Image (Paling HD)
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

        // Cek apakah Series atau Movie
        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }
        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epText = element.text()
                // Regex untuk ambil nomor episode
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

    // --- EKSTRAKSI LINK (BAGIAN KRUSIAL) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. SCAN IFRAME UTAMA (Solusi Masalah LiteSpeed)
        // Website ini menyembunyikan link di atribut 'data-litespeed-src'
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("data-litespeed-src") // Prioritas 1
            if (src.isEmpty()) src = iframe.attr("data-src") // Prioritas 2
            if (src.isEmpty()) src = iframe.attr("src")      // Prioritas 3
            
            val fixedSrc = fixUrl(src)
            // Filter link sampah (iklan/tracking)
            if (isValidLink(fixedSrc)) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        // 2. SCAN LINK DOWNLOAD
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        // 3. SCAN SERVER TAMBAHAN (AJAX Attack)
        // Kita butuh Post ID untuk request ke server
        val postId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")
            ?: document.selectFirst("link[rel='shortlink']")?.attr("href")?.substringAfter("?p=")

        if (postId != null) {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val serverTabs = document.select(".muvipro-player-tabs li")
            
            serverTabs.forEach { tab ->
                val nume = tab.attr("data-nume")
                if (nume.isNotEmpty()) {
                    try {
                        // Tembak AJAX dengan Header Lengkap (Wajib ada Referer!)
                        val jsonResponse = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "tab" to "player-option-$nume",
                                "post_id" to postId
                            ),
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Referer" to data, // Header KTP biar server tidak menolak
                                "Content-Type" to "application/x-www-form-urlencoded"
                            )
                        ).text

                        // Parsing HTML dari respon AJAX
                        val ajaxDoc = Jsoup.parse(jsonResponse)
                        ajaxDoc.select("iframe").forEach { iframe ->
                            var src = iframe.attr("data-litespeed-src")
                            if (src.isEmpty()) src = iframe.attr("src")
                            
                            val fixedSrc = fixUrl(src)
                            if (isValidLink(fixedSrc)) {
                                loadExtractor(fixedSrc, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        // Lanjut ke server berikutnya jika error
                    }
                }
            }
        }

        return true
    }

    // Filter untuk membuang link yang bukan video
    private fun isValidLink(url: String): Boolean {
        return url.isNotBlank() && 
               !url.contains("youtube.com") && 
               !url.contains("googletagmanager") &&
               !url.contains("facebook.com") &&
               !url.contains("about:blank")
    }
}
