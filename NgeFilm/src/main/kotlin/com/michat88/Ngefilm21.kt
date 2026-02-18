package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// --- PROVIDER UTAMA ---
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

    // Helper untuk poster resolusi tinggi
    private fun Element.getImageAttr(): String? {
        val srcset = this.attr("srcset")
        if (srcset.isNotEmpty()) {
            return try {
                srcset.split(",")
                    .map { it.trim().split(" ") }
                    .filter { it.size >= 2 }
                    .maxByOrNull { it[1].replace("w", "").toIntOrNull() ?: 0 }
                    ?.get(0)
            } catch (e: Exception) { this.attr("src") }
        }
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

        // --- PANGGIL CUSTOM EXTRACTOR (NgefilmKraken) ---
        // Cari URL Embed Kraken: https://krakenfiles.com/embed-video/ID
        val krakenRegex = Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""")
        
        krakenRegex.findAll(rawHtml).forEach { match ->
            val embedUrl = match.groupValues[1]
            // Panggil class extractor khusus kita di bawah
            NgefilmKraken().getUrl(embedUrl, null, subtitleCallback, callback)
        }

        // --- Fallback untuk Iframe Umum ---
        val document = org.jsoup.Jsoup.parse(rawHtml)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            // Hindari double process untuk Kraken (karena sudah dihandle NgefilmKraken)
            if (!fixedSrc.contains("krakenfiles.com") && !fixedSrc.contains("youtube.com")) { 
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        return true
    }
}

// --- CUSTOM EXTRACTOR KHUSUS KRAKEN ---
// Ini meniru struktur Krakenfiles.kt tetapi dengan Headers & Regex yang sudah kita perbaiki
open class NgefilmKraken : ExtractorApi() {
    override val name = "Krakenfiles (Custom)"
    override val mainUrl = "https://krakenfiles.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Headers ini WAJIB agar tidak diblokir (Sama seperti Script Python)
        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://new31.ngefilm.site/",
            "Origin" to "https://new31.ngefilm.site",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )

        // Request ke halaman embed dengan headers yang benar
        val response = app.get(url, headers = customHeaders).text

        // Regex V2: Mencari pola link video yang valid (bukan .mp4 text biasa)
        // Sesuai temuan: src="https://phs9.krakencloud.net/play/video/..."
        val videoRegex = Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""")
        val match = videoRegex.find(response)
        
        // Backup Regex jika pola pertama gagal (kadang di data-src-url)
        val finalUrl = if (match != null) {
            match.groupValues[1]
        } else {
            Regex("""data-src-url=["'](https:[^"']+)["']""").find(response)?.groupValues?.get(1)
        }

        if (finalUrl != null) {
            // Bersihkan URL dari backslash (jika ada)
            val cleanUrl = finalUrl.replace("\\", "")
            
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    [span_2](start_span)httpsify(cleanUrl), //[span_2](end_span)
                    [span_3](start_span)ExtractorLinkType.VIDEO //[span_3](end_span)
                ) {
                    [span_4](start_span)this.referer = url //[span_4](end_span)
                    [span_5](start_span)this.quality = Qualities.Unknown.value //[span_5](end_span) - Memperbaiki error parameter 'quality'
                }
            )
        }
    }
}
