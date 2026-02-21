package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.util.Base64

class RebahinProvider : MainAPI() {

    // ================================================================
    // KONFIGURASI DASAR
    // ================================================================
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name   = "Rebahin"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    data class ApiPingResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("sources") val sources: List<ApiSource>? = null
    )

    data class ApiSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    // ================================================================
    // HALAMAN UTAMA
    // ================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/trending/"           to "Trending",
        "$mainUrl/movie/"              to "Film Terbaru",
        "$mainUrl/series/"             to "Series Terbaru",
        "$mainUrl/genre/action/"       to "Action",
        "$mainUrl/genre/comedy/"       to "Comedy",
        "$mainUrl/genre/drama/"        to "Drama",
        "$mainUrl/genre/horror/"       to "Horror",
        "$mainUrl/genre/romance/"      to "Romance",
        "$mainUrl/genre/thriller/"     to "Thriller",
        "$mainUrl/genre/korean-drama/" to "Korean Drama",
        "$mainUrl/genre/animation/"    to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        
        val home = document.select(".ml-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href")
        val title = a.attr("title").ifEmpty { this.selectFirst("h2")?.text() } ?: return null
        
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        
        val type = if (
            this.selectFirst(".mli-eps") != null ||
            this.selectFirst(".gmr-episode-item") != null ||
            href.contains("/series/") || href.contains("/tv/")
        ) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) { 
            this.posterUrl = poster 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to userAgent))
            .document.select(".ml-item").mapNotNull { it.toSearchResult() }
    }

    // ================================================================
    // DETAIL FILM / SERIES
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = doc.selectFirst("h3[itemprop=name]")?.attr("content") 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" |")
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("a.mvi-cover")?.attr("style")?.let { Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1) }

        val plot = doc.selectFirst("div[itemprop=description], div.desc")?.text()
        val rating = doc.selectFirst("span[itemprop=ratingValue], span.irank-voters")?.text()
        val genres = doc.select("span[itemprop=genre]").map { it.text() }
        val year = doc.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()

        val trailerUrl = doc.selectFirst("iframe#iframe-trailer")?.attr("src")
        val isValidTrailer = trailerUrl != null && trailerUrl.contains("youtube") && !trailerUrl.contains("http://googleusercontent.com/youtube.com")

        val isSeries = url.contains("/series/") || url.contains("/tv/") || doc.selectFirst("ul.episodios") != null

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select("ul.episodios li a, div.gmr-episodelist a, #list-eps a, a.btn-eps").forEachIndexed { index, ep ->
                val epUrl = ep.attr("href")
                if (epUrl.isNotEmpty()) {
                    val epText = ep.text()
                    val epNum = Regex("Episode\\s*(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("[?&]ep=(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() 
                        ?: (index + 1)
                    episodes.add(newEpisode(epUrl) {
                        this.name    = epText.ifEmpty { "Episode $epNum" }
                        this.season  = 1
                        this.episode = epNum
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating) 
                this.tags = genres
                this.year = year
                if (isValidTrailer && trailerUrl != null) {
                    this.trailers.add(TrailerData(trailerUrl, null, false))
                }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.tags = genres
                this.year = year
                if (isValidTrailer && trailerUrl != null) {
                    this.trailers.add(TrailerData(trailerUrl, null, false))
                }
            }
        }
    }

    // ================================================================
    // EKSTRAK LINK VIDEO (MENGGUNAKAN WEBVIEW RESOLVER)
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playDoc = app.get(
            data,
            headers = mapOf(
                "User-Agent"      to userAgent,
                "Accept-Language" to "id-ID,id;q=0.9",
                "Referer"         to mainUrl,
            )
        ).document

        var handled = false

        playDoc.select(".server[data-iframe]").forEach { serverTag ->
            try {
                val base64Iframe = serverTag.attr("data-iframe")
                if (base64Iframe.isNotEmpty()) {
                    val embedUrl = runCatching {
                        String(Base64.getDecoder().decode(base64Iframe))
                    }.getOrNull()

                    if (embedUrl != null) {
                        handled = true
                        // Semua server sekarang diproses dengan WebView agar aman
                        processExternalEmbed(embedUrl, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!handled) {
            playDoc.select("iframe[src]").forEach { iframe ->
                try {
                    val src = iframe.attr("src")
                    if (src.isNotEmpty() && !src.contains("youtube") && !src.contains("googleusercontent")) {
                        processExternalEmbed(src, data, subtitleCallback, callback)
                        handled = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return handled
    }

    private suspend fun processExternalEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // WEBVIEW MAGIC: Intercept semua domain video yang kita temukan tadi
            // Ini akan otomatis menangani Dawn/Flow Ping dan Service Worker Abyss
            val interceptor = WebViewResolver(
                Regex(".*daisy\\.groovy\\.monster.*|.*\\.sssrr\\.org.*|.*\\.m3u8.*|.*\\.mp4.*|.*abysscdn\\.com.*")
            )

            // Buka halaman dan biarkan JavaScript bekerja selama 30 detik
            val response = app.get(embedUrl, referer = referer, interceptor = interceptor, timeout = 30)
            val finalUrl = response.url

            // Jika link video asli ditemukan melalui interceptor
            if (finalUrl.contains("daisy.groovy.monster") || finalUrl.contains("sssrr.org") || finalUrl.contains(".m3u8")) {
                val isJuicy = finalUrl.contains("daisy")
                val isAbyss = finalUrl.contains("sssrr")

                callback.invoke(
                    newExtractorLink(
                        source  = name,
                        name    = if (isJuicy) "Server Juicy" else if (isAbyss) "Server Abyss" else "Premium Server",
                        url     = finalUrl,
                        referer = embedUrl,
                        type    = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer"    to embedUrl,
                            "Origin"     to embedUrl.substringBefore("/embed")
                        )
                    }
                )
            } else {
                // Jika WebView gagal menangkap link dinamis, coba ekstraktor standar
                loadExtractor(embedUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        }
    }

    // Fungsi helper tambahan untuk kualitas link standar
    private suspend fun buildExtractorLink(
        url: String,
        referer: String,
        isM3u8: Boolean,
        quality: Int = Qualities.Unknown.value
    ): ExtractorLink {
        return newExtractorLink(
            source  = name,
            name    = if (isM3u8) "$name HLS" else "$name MP4",
            url     = url,
            type    = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf(
                "User-Agent" to userAgent,
                "Referer"    to referer
            )
        }
    }
}
