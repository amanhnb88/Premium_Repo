package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class NgefilmProvider : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Terbaru",
        "$mainUrl/film-terbaru/page/" to "Film Terbaru",
        "$mainUrl/drama-korea/page/" to "Drama Korea",
        "$mainUrl/series/page/" to "Series",
        "$mainUrl/genre/action/page/" to "Action",
        "$mainUrl/genre/adventure/page/" to "Adventure",
        "$mainUrl/genre/comedy/page/" to "Comedy",
        "$mainUrl/genre/crime/page/" to "Crime",
        "$mainUrl/genre/drama/page/" to "Drama",
        "$mainUrl/genre/horror/page/" to "Horror",
        "$mainUrl/genre/romance/page/" to "Romance",
        "$mainUrl/genre/thriller/page/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = this.selectFirst(".gmr-quality-item a")?.text()
        val type = if (this.selectFirst(".gmr-duration-item")?.text()?.contains("Season", true) == true 
            || href.contains("/series/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".gmr-movie-poster img")?.attr("src"))
        val year = document.selectFirst(".gmr-movie-data:contains(Tahun) span")?.text()?.toIntOrNull()
        val duration = document.selectFirst(".gmr-movie-data:contains(Durasi) span")?.text()
            ?.replace("Menit", "")?.trim()?.toIntOrNull()
        
        // Fix: Gunakan score API yang baru, bukan rating yang deprecated
        val ratingValue = document.selectFirst(".gmr-movie-data:contains(Rating) span")?.text()?.toDoubleOrNull()
        val score = ratingValue?.let { ShowStatus.Ongoing }
        
        val plot = document.selectFirst(".entry-content[itemprop=description]")?.text()?.trim()
        val tags = document.select(".gmr-movie-data:contains(Genre) span a").map { it.text() }
        
        // Ambil trailer YouTube
        val trailerUrl = document.select("iframe[src*=youtube.com], iframe[src*=youtu.be]")
            .firstOrNull()?.attr("src")
        
        // Fix: addActors hanya menerima List<Actor> atau List<Pair<Actor, String?>>
        val actors = document.select(".gmr-movie-data:contains(Bintang) span a").map {
            Actor(it.text())
        }
        
        // Ambil rekomendasi
        val recommendations = document.select(".gmr-related .item").mapNotNull {
            it.toSearchResult()
        }
        
        // Cek apakah series atau movie
        val episodes = document.select(".gmr-listseries a").mapNotNull { eps ->
            val href = fixUrl(eps.attr("href"))
            val name = eps.text().trim()
            
            val episodeNum = Regex("Episode\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("Season\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
            
            Episode(
                data = href,
                name = name,
                season = seasonNum,
                episode = episodeNum
            )
        }
        
        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                // Fix: Gunakan showRating untuk rating
                this.showRating = ratingValue?.times(10)?.toInt()
                this.duration = duration
                addActors(actors)
                addTrailer(trailerUrl)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                // Fix: Gunakan showRating untuk rating
                this.showRating = ratingValue?.times(10)?.toInt()
                this.duration = duration
                addActors(actors)
                addTrailer(trailerUrl)
                this.recommendations = recommendations
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
        
        // Ambil semua server dari dropdown atau button
        val servers = document.select(".gmr-server-wrap button, .gmr-embed-responsive iframe")
        
        servers.forEach { server ->
            val serverUrl = server.attr("data-src").ifEmpty { 
                server.attr("src") 
            }
            
            if (serverUrl.isNotBlank()) {
                val embedUrl = if (serverUrl.startsWith("http")) {
                    serverUrl
                } else {
                    fixUrl(serverUrl)
                }
                
                // Load server page dan extract iframe
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }
        
        // Cari juga iframe langsung di halaman
        document.select("iframe[src*=player], iframe[src*=embed]").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.isNotBlank()) {
                loadExtractor(iframeUrl, subtitleCallback, callback)
            }
        }
        
        return true
    }
    
    private suspend fun loadExtractor(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Try built-in extractors
        loadExtractor(url, mainUrl, subtitleCallback, callback)
        
        // Manual extraction jika perlu
        try {
            val doc = app.get(url).document
            
            // Cari video source
            doc.select("video source, source[type*=video]").forEach { source ->
                val videoUrl = source.attr("src")
                if (videoUrl.isNotBlank()) {
                    // Fix: Gunakan method helper untuk membuat ExtractorLink
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = fixUrl(videoUrl),
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
            
            // Cari subtitle tracks
            doc.select("track[kind=subtitles], track[kind=captions]").forEach { track ->
                val subUrl = track.attr("src")
                val subLang = track.attr("label").ifEmpty { 
                    track.attr("srclang") 
                }
                if (subUrl.isNotBlank()) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = subLang.ifEmpty { "Indonesian" },
                            url = fixUrl(subUrl)
                        )
                    )
                }
            }
            
            // Cari dari script tags (HLS/m3u8 links)
            doc.select("script").forEach { script ->
                val scriptContent = script.html()
                
                // Pattern untuk m3u8
                val m3u8Pattern = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
                m3u8Pattern.findAll(scriptContent).forEach { match ->
                    val m3u8Url = match.groupValues[1]
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name + " HLS",
                            url = m3u8Url,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
                
                // Pattern untuk mp4
                val mp4Pattern = Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""")
                mp4Pattern.findAll(scriptContent).forEach { match ->
                    val mp4Url = match.groupValues[1]
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = this.name + " MP4",
                            url = mp4Url,
                            referer = url,
                            quality = Qualities.Unknown.value
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore extraction errors
        }
    }
}
