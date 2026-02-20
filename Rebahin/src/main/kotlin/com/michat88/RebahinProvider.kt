package com.michat88

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

    // Bagian yang diperbarui: Daftar kategori halaman utama
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
        val url = if (page == 1) request.data.removeSuffix("page/") else request.data + page
        val document = app.get(url).document

        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.ml-item, div.result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title") ?: this.selectFirst("span.mli-info h2")?.text() ?: return null
        
        val imgNode = this.selectFirst("img")
        var posterUrl = imgNode?.attr("data-original")
        if (posterUrl.isNullOrBlank()) posterUrl = imgNode?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = imgNode?.attr("src")
        
        val quality = this.selectFirst("span.mli-quality")?.text()
        
        val isTvSeries = href.contains("/series/") || href.contains("/tv/") || href.contains("/episode/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // 1. HALAMAN DETAIL (METADATA & TRAILER)
        val document = app.get(url).document

        var title = document.selectFirst("meta[property=og:title]")?.attr("content")
        if (title != null) {
            title = title.substringBefore(" |").replace("Nonton Film ", "").replace("Nonton Series ", "").trim()
        } else {
            title = document.selectFirst("meta[itemprop=name]")?.attr("content") 
                ?: document.selectFirst("h3")?.text() 
                ?: "Unknown"
        }
            
        var poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (poster.isNullOrBlank()) poster = document.selectFirst("meta[itemprop=image]")?.attr("content")
        if (poster.isNullOrBlank()) poster = document.selectFirst("div.mvic-thumb img")?.attr("data-original")
        if (poster.isNullOrBlank()) poster = document.selectFirst("div.mvic-thumb img")?.attr("src")
        
        val year = document.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
        
        val plot = document.select("div.desc-des-pendek p").joinToString("\n") { it.text() }.trim().ifEmpty { 
            document.selectFirst("div.desc")?.text() ?: ""
        }
        
        val ratingText = document.selectFirst("div.averagerate")?.text()
        val tagsList = document.select("span[itemprop=genre]").map { it.text() }

        // MENGAMBIL TRAILER YOUTUBE
        var trailerUrl = document.selectFirst("iframe#iframe-trailer")?.attr("src")
        if (trailerUrl != null && trailerUrl.contains("youtube.com")) {
            if (trailerUrl.startsWith("//")) trailerUrl = "https:$trailerUrl"
        } else {
            trailerUrl = null
        }

        val isTvSeries = url.contains("/series/") || document.selectFirst("div#list-eps") != null

        // 2. MENCARI URL HALAMAN 'PLAY' ATAU 'WATCH'
        var playUrl = document.selectFirst("#mv-info a.mvi-cover")?.attr("href")
            ?: document.selectFirst("a.bwac-btn, div.bwa-content a, a.play-btn")?.attr("href")
            
        if (playUrl.isNullOrBlank()) {
            val baseUrl = url.removeSuffix("/")
            playUrl = if (isTvSeries) "$baseUrl/watch/" else "$baseUrl/play/"
        }
        playUrl = fixUrl(playUrl)

        if (isTvSeries) {
            val playDoc = app.get(playUrl).document
            val episodes = mutableListOf<Episode>()
            val epsMap = mutableMapOf<Int, MutableList<String>>()
            
            val seasonMatch = Regex("""Season\s+(\d+)""", RegexOption.IGNORE_CASE).find(title)
            val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()

            playDoc.select("div#list-eps a.btn-eps, div.list-eps a.btn-eps").forEach { epsNode ->
                val encodedIframe = epsNode.attr("data-iframe")
                if (encodedIframe.isNotBlank()) {
                    val epName = epsNode.text()
                    val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    val decodedUrl = String(Base64.decode(encodedIframe, Base64.DEFAULT))
                    
                    if (epsMap[epNum] == null) {
                        epsMap[epNum] = mutableListOf()
                    }
                    epsMap[epNum]?.add(decodedUrl)
                }
            }
            
            epsMap.forEach { (epNum, urlList) ->
                episodes.add(
                    newEpisode(urlList.toJson()) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }

            if (episodes.isEmpty()) {
                val servers = playDoc.select("div.server-wrapper div.server")
                if (servers.isNotEmpty()) {
                    val urlList = servers.mapNotNull { 
                        val encoded = it.attr("data-iframe")
                        if (encoded.isNotBlank()) String(Base64.decode(encoded, Base64.DEFAULT)) else null
                    }
                    if (urlList.isNotEmpty()) {
                        episodes.add(
                            newEpisode(urlList.toJson()) {
                                this.name = "Episode 1"
                                this.episode = 1
                                this.season = seasonNum ?: 1
                            }
                        )
                    }
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingText, 5)
                this.tags = tagsList
                // PERBAIKAN: Menggunakan fungsi addTrailer bawaan Cloudstream API
                addTrailer(trailerUrl) 
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.score = Score.from(ratingText, 5)
                this.tags = tagsList
                // PERBAIKAN: Menggunakan fungsi addTrailer bawaan Cloudstream API
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
        
        if (data.startsWith("[")) {
            val urls = tryParseJson<List<String>>(data)
            urls?.forEach { url ->
                extractVideoLinks(url, mainUrl, subtitleCallback, callback)
            }
            return true
        }

        val document = app.get(data).document
        val servers = document.select("div.server-wrapper div.server")

        servers.forEach { server ->
            val encodedIframe = server.attr("data-iframe")
            if (encodedIframe.isNotBlank()) {
                try {
                    val decodedUrl = String(Base64.decode(encodedIframe, Base64.DEFAULT))
                    extractVideoLinks(decodedUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }

    private suspend fun extractVideoLinks(
        url: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return

        try {
            var finalUrl = url
            
            if (finalUrl.contains("short.icu")) {
                finalUrl = app.get(finalUrl, allowRedirects = true).url 
            }

            loadExtractor(finalUrl, referer, subtitleCallback, callback)

            val ipRegex = Regex("""https?://\d+\.\d+\.\d+\.\d+/.*""")
            
            if (ipRegex.matches(finalUrl) || finalUrl.contains("abysscdn.com")) {
                val embedText = app.get(finalUrl, referer = referer).text
                val unpacked = getAndUnpack(embedText)
                
                val videoRegex = """["']?(?:file|source)["']?\s*:\s*["'](https?://[^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                
                val allMatches = videoRegex.findAll(unpacked).map { it.groupValues[1] }.toList() + 
                                 videoRegex.findAll(embedText).map { it.groupValues[1] }.toList()
                
                val streamUrl = allMatches.firstOrNull { 
                    !it.endsWith(".vtt") && !it.endsWith(".srt") && !it.endsWith(".jpg") && !it.endsWith(".png") 
                }
                
                streamUrl?.let {
                    val isM3u = it.contains(".m3u8") || it.contains("/stream/") || it.contains("hls")
                    val sourceName = if (finalUrl.contains("abysscdn")) "AbyssCDN (HD)" else "Rebahin VIP"
                    
                    callback.invoke(
                        newExtractorLink(
                            source = this@RebahinProvider.name,
                            name = sourceName,
                            url = it,
                            type = if (isM3u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = finalUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
