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

    // Helper untuk mengambil gambar (menangani lazy load dan resolusi)
    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty {
            this.attr("src")
        }
        // Menghapus suffix ukuran gambar (misal -152x228) agar dapat kualitas HD
        return url.replace(Regex("-\\d+x\\d+"), "")
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
            // Menggunakan Score.from10 untuk sistem rating baru
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // URL Search pattern berdasarkan form di HTML
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document

        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Mengambil Detail Utama
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()

        // 2. Mengambil Genre dan Aktor
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        
        // Fix Error Actor: Map ke ActorData
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) {
                ActorData(Actor(name, null))
            } else null
        }

        // 3. Mengambil Trailer (Prioritas: Tombol Popup -> Iframe Youtube)
        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) {
            trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")
        }

        // 4. Logika TV Series vs Movie
        // Memfilter link episode yang valid (mengabaikan tombol 'Pilih Episode')
        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }

        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epText = element.text() // Contoh: "Eps1"
                
                // Ambil angka dari teks "Eps1" -> 1
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")

                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                } else null
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
                addTrailer(trailerUrl)
            }
        } else {
            // Untuk Movie, URL konten sama dengan URL halaman
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
                addTrailer(trailerUrl)
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

        // 1. Cek Iframe langsung (Metode Tradisional)
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            
            // Filter link sampah (youtube trailer, wordpress embed)
            if (!src.contains("youtube.com") && !src.contains("wp-embedded-content")) {
                loadExtractor(src, subtitleCallback, callback)
            }
        }

        // 2. Cek Navigasi Player (Metode Tema MuviPro AJAX)
        val serverIds = document.select(".gmr-player-nav li a").mapNotNull { 
            val postId = it.attr("data-post")
            val num = it.attr("data-nume")
            if (postId.isNotEmpty() && num.isNotEmpty()) postId to num else null
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
                    // Melakukan Request POST ke server untuk mendapatkan HTML player
                    val response = app.post(ajaxUrl, data = formData).text
                    // Parsing hasil response (biasanya berisi iframe)
                    val iframeSrc = Jsoup.parse(response).select("iframe").attr("src")
                    
                    if (iframeSrc.isNotEmpty()) {
                         loadExtractor(iframeSrc, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Abaikan error jika satu server gagal
                }
            }
        }

        return true
    }
}
