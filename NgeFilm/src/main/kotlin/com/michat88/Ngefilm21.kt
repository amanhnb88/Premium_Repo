package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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

    // --- FIX POSTER KUALITAS TERBAIK ---
    private fun Element.getImageAttr(): String? {
        // 1. Coba ambil dari srcset (biasanya berisi daftar ukuran gambar)
        val srcset = this.attr("srcset")
        if (srcset.isNotEmpty()) {
            return try {
                // Format srcset: "url1 width1, url2 width2, ..."
                // Kita split, ambil width-nya, urutkan dari terbesar, ambil URL-nya
                srcset.split(",")
                    .map { it.trim().split(" ") }
                    .filter { it.size >= 2 } // Pastikan ada url dan width
                    .maxByOrNull { it[1].replace("w", "").toIntOrNull() ?: 0 } // Cari width terbesar
                    ?.get(0) // Ambil URL-nya
            } catch (e: Exception) {
                // Jika gagal parsing, lanjut ke bawah
                this.attr("src")
            }
        }
        
        // 2. Fallback: Ambil src biasa jika srcset kosong
        return this.attr("data-src").ifEmpty { this.attr("src") }
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
        
        // GUNAKAN FUNGSI FIX POSTER DI SINI
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
        
        // GUNAKAN FUNGSI FIX POSTER DI SINI JUGA
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) ActorData(Actor(name, null)) else null
        }

        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) {
            trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")
        }

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
                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                } else null
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
            }
        }

        if (trailerUrl != null) {
             response.trailers.add(TrailerData(trailerUrl, null, false))
        }

        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawHtml = app.get(data).text

        // --- SERVER 4 (KRAKENFILES) ---
        // Pola: src="https://krakenfiles.com/embed-video/ID"
        val krakenRegex = Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""")
        
        krakenRegex.findAll(rawHtml).forEach { match ->
            val embedUrl = match.groupValues[1]
            
            // 1. Coba serahkan ke CloudStream (Bawaan)
            // Ini menggunakan ExtractorApi.kt yang kamu kirim
            loadExtractor(embedUrl, subtitleCallback, callback)

            // 2. Coba Manual sebagai Backup (Jika bawaan gagal)
            extractKrakenManual(embedUrl, callback)
        }

        // --- Fallback Iframe Umum ---
        val document = org.jsoup.Jsoup.parse(rawHtml)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            if (!fixedSrc.contains("youtube.com") && 
                !fixedSrc.contains("krakenfiles.com")) { // Kraken sudah dihandle regex di atas
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://new31.ngefilm.site/",
                "Origin" to "https://new31.ngefilm.site"
            )

            val responseText = app.get(url, headers = headers).text

            // Pola HTML Kraken: <source src="https://..." type="video/mp4">
            // Kita pakai Regex yang sedikit lebih longgar untuk menangkap URL
            val videoRegex = Regex("""<source[^>]+src=["'](https:[^"']+)["']""")
            val match = videoRegex.find(responseText)

            if (match != null) {
                var videoUrl = match.groupValues[1]
                videoUrl = videoUrl.replace("&amp;", "&") // Fix HTML entities

                callback.invoke(
                    newExtractorLink(
                        source = "Krakenfiles",
                        name = "Krakenfiles (Backup)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
