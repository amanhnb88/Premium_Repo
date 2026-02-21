package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HomeCookingRocks : MainAPI() {
    
    override var name = "Home Cooking Rocks"
    override var mainUrl = "https://homecookingrocks.com"
    override var supportedTypes = setOf(TvType.Others, TvType.Movie) 
    override var lang = "id"
    override val hasMainPage = true
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Kelas Bintang",
        "$mainUrl/category/semi-barat/" to "Barat Punya",
        "$mainUrl/category/bokep-indo/" to "Indo Punya",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam Punya"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val elements = document.select("#gmr-main-load article")
        
        val home = elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = image
            }
        }
        return newHomePageResponse(request.name, home, hasNext = elements.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("#gmr-main-load article").mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val url = titleElement.attr("href")
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun:) a")?.text()?.toIntOrNull()
        val ratingString = document.selectFirst(".gmr-meta-rating span[itemprop=ratingValue]")?.text()
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(ratingString)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select(".muvipro-player-tabs li a")

        for (server in servers) {
            // FIX: Tambahkan try-catch agar kalau server error, aplikasi nggak force close
            try {
                val serverUrl = fixUrl(server.attr("href"))
                val serverDoc = if (serverUrl == data) document else app.get(serverUrl).document
                val iframeSrc = serverDoc.selectFirst(".gmr-embed-responsive iframe")?.attr("src")

                if (iframeSrc != null) {
                    // ==========================================
                    // SERVER 1: Pyrox
                    // ==========================================
                    if (iframeSrc.contains("embedpyrox") || iframeSrc.contains("pyrox")) {
                        val iframeId = iframeSrc.substringAfterLast("/")
                        val host = java.net.URI(iframeSrc).host
                        val apiUrl = "https://$host/player/index.php?data=$iframeId&do=getVideo"

                        val response = app.post(
                            url = apiUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to iframeSrc),
                            data = mapOf("hash" to iframeId, "r" to data)
                        ).text

                        val m3u8Url = Regex("""(https:\\?/\\?/[^"]+(?:master\.txt|\.m3u8))""")
                            .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                        if (m3u8Url != null) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "Server 1 (Pyrox)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = iframeSrc
                                    this.quality = Qualities.Unknown.value
                                    // FIX: Tambahkan Headers agar tidak kena M3U8 Error
                                    this.headers = mapOf(
                                        "Origin" to "https://$host",
                                        "Accept" to "*/*"
                                    )
                                }
                            )
                        }
                    } 
                    // ==========================================
                    // SERVER 2 & 3: 4MePlayer & ImaxStreams
                    // ==========================================
                    else if (iframeSrc.contains("4meplayer") || iframeSrc.contains("imaxstreams")) {
                        // SENJATA RAHASIA CLOUDSTREAM: WebViewResolver!
                        // Ini akan merender halaman secara tak kasat mata dan merampok link m3u8 nya.
                        val serverName = if (iframeSrc.contains("4meplayer")) "Server 2 (4MePlayer)" else "Server 3/4 (ImaxStreams)"
                        
                        val response = app.get(
                            url = iframeSrc,
                            referer = data,
                            interceptor = com.lagradost.cloudstream3.network.WebViewResolver(Regex("""\.m3u8"""))
                        )
                        val m3u8Url = response.url
                        
                        if (m3u8Url.contains(".m3u8")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = serverName,
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = iframeSrc
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                    // ==========================================
                    // DEFAULT: Server Lainnya
                    // ==========================================
                    else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Biarkan saja kalau error, lanjut cari link di server lain
                e.printStackTrace()
            }
        }
        return true
    }
}
