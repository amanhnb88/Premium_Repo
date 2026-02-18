package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // --- FIX 1: POSTER ANTI-404 ---
    private fun Element.getImageAttr(): String? {
        // Coba ambil dari srcset (daftar ukuran gambar resmi)
        val srcset = this.attr("srcset")
        if (srcset.isNotEmpty()) {
            return try {
                srcset.split(",")
                    .map { it.trim().split(" ") }
                    .filter { it.size >= 2 }
                    .maxByOrNull { it[1].replace("w", "").toIntOrNull() ?: 0 } // Pilih yang paling besar
                    ?.get(0)
            } catch (e: Exception) { this.attr("src") }
        }
        // Fallback: ambil src biasa, JANGAN dihapus resolusinya biar gak 404
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
        
        // Gunakan fix poster di sini
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

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epNum = Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")
                if (epUrl.isNotEmpty()) newEpisode(epUrl) { this.name = epName; this.episode = epNum } else null
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
                if (trailerUrl != null) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        }
    }

    // --- LOGIKA UTAMA LOAD LINKS (MULTI-FETCH) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // 1. Ambil semua link player dari Tab (Server 1, 2, 3, 4, dst)
        val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { 
            it.attr("href") 
        }.toMutableList()

        // Tambahkan halaman saat ini (takutnya tabnya gak lengkap)
        if (playerLinks.isEmpty()) playerLinks.add(data)
        // Pastikan link unik
        val uniqueLinks = playerLinks.distinct()

        // 2. Loop setiap link player, fetch halamannya, lalu cari videonya
        uniqueLinks.apmap { playerUrl ->
            try {
                // Fix URL relative
                val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                val pageContent = app.get(fixedUrl).text
                
                // --- PROSES KRAKENFILES (SERVER 4) ---
                val krakenRegex = Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""")
                krakenRegex.findAll(pageContent).forEach { match ->
                    extractKrakenManual(match.groupValues[1], callback)
                }

                // --- PROSES MIXDROP (SERVER 5) ---
                val mixdropRegex = Regex("""src=["'](https://(?:xshotcok\.com|mixdrop\.[a-z]+)/embed-[^"']+)["']""")
                mixdropRegex.findAll(pageContent).forEach { match ->
                    loadExtractor(match.groupValues[1], subtitleCallback, callback)
                }

                // --- PROSES ABYSS / SHORT.ICU (SERVER 2) ---
                val abyssRegex = Regex("""src=["'](https://short\.icu/[^"']+)["']""")
                abyssRegex.findAll(pageContent).forEach { match ->
                    extractAbyssManual(match.groupValues[1], callback)
                }
                
                // --- PROSES VIBUXER (SERVER 3) ---
                val vibuxerRegex = Regex("""src=["'](https://(?:hgcloud\.to|vibuxer\.com)/e/[^"']+)["']""")
                vibuxerRegex.findAll(pageContent).forEach { match ->
                    loadExtractor(match.groupValues[1], subtitleCallback, callback)
                }

            } catch (e: Exception) {
                // Ignore error per page fetch
            }
        }

        return true
    }

    // --- MANUAL KRAKEN (Header Trick) ---
    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://new31.ngefilm.site/"
            )
            val text = app.get(url, headers = headers).text
            
            // Cari source video
            val videoUrl = Regex("""<source[^>]+src=["'](https:[^"']+)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""").find(text)?.groupValues?.get(1)

            if (videoUrl != null) {
                callback.invoke(newExtractorLink(
                    source = "Krakenfiles",
                    name = "Krakenfiles",
                    url = videoUrl.replace("&amp;", "&"),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {}
    }

    // --- MANUAL ABYSS (Follow Redirect) ---
    private suspend fun extractAbyssManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val videoId = url.substringAfterLast("/")
            val abyssUrl = "https://abysscdn.com/?v=$videoId"
            val text = app.get(abyssUrl, headers = mapOf("Referer" to mainUrl)).text
            
            Regex("""file:\s*["'](https://[^"']+\.mp4[^"']*)["']""").find(text)?.groupValues?.get(1)?.let { mp4 ->
                callback.invoke(newExtractorLink("Abyss", "Abyss", mp4, ExtractorLinkType.VIDEO) {
                    this.referer = "https://abysscdn.com/"
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {}
    }
}
