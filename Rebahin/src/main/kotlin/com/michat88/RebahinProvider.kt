package com.michat88

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie, 
        TvType.TvSeries, 
        TvType.Anime, 
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/country/indonesia/page/" to "Film Indo",
        "$mainUrl/genre/series-indonesia/page/" to "SeriesTV Indo",
        "$mainUrl/genre/action/page/" to "Action Movie",
        "$mainUrl/genre/adventure/page/" to "Petualangan",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/country/south-korea/page/" to "Korea",
        "$mainUrl/genre/adult/page/" to "VivaMax"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.ml-item, article.item, div.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.ml-item, article.item, div.item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val url = aTag.attr("href")
        val title = aTag.attr("title").ifEmpty { 
            this.selectFirst("img")?.attr("alt") 
        } ?: this.text()
        val posterUrl = this.selectFirst("img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") }
        }

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1[itemprop=name], h3[itemprop=name], h1.entry-title, h1.title")?.text() ?: "Judul Tidak Diketahui"
        val poster = document.selectFirst("img[itemprop=image], div.mvic-thumb img, div.poster img")?.attr("src")
        val plot = document.selectFirst("div.mvic-desc, div.f-desc, div.entry-content")?.text()
        
        val playUrl = if (url.endsWith("/")) "${url}play/" else "$url/play/"

        return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            
            // Mencari server embed dari atribut data-iframe
            val servers = document.select("div[data-iframe]")
            
            servers.forEach { element ->
                val base64Url = element.attr("data-iframe")
                
                if (base64Url.isNotBlank()) {
                    val finalUrl = String(Base64.decode(base64Url, Base64.DEFAULT))
                    
                    // 1. Ekstraktor bawaan Cloudstream (Kemungkinan besar ini yang akan menangkap videonya)
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                    
                    // 2. Backup manual pencarian regex jika extractor bawaan gagal
                    val embedText = app.get(finalUrl).text
                    val videoRegex = """["']?(?:file|source)["']?\s*:\s*["'](https?://[^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                    
                    val allMatches = videoRegex.findAll(embedText).map { it.groupValues[1] }.toList()
                    val streamUrl = allMatches.firstOrNull { 
                        !it.endsWith(".vtt") && !it.endsWith(".srt") && !it.endsWith(".jpg") && !it.endsWith(".png") 
                    }
                    
                    streamUrl?.let { url ->
                        val isM3u = url.contains(".m3u8") || url.contains("/stream/") || url.contains("hls")
                        val sourceName = if (finalUrl.contains("abysscdn")) "AbyssCDN (HD)" else "Rebahin VIP"
                        
                        // MENGGUNAKAN STANDAR BARU (Sesuai snippet awal Anda)
                        callback.invoke(
                            newExtractorLink(
                                source = this@RebahinProvider.name,
                                name = sourceName,
                                url = url,
                                type = if (isM3u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = finalUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
