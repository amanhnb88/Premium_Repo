package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HomeCookingRocks : MainAPI() {
    override var name = "Home Cooking Rocks"
    override var mainUrl = "https://homecookingrocks.com"
    override var supportedTypes = setOf(TvType.NSFW, TvType.Movie) 
    override var lang = "id"
    override val hasMainPage = true
    
    override val mainPage = mainPageOf(
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Kelas Bintang",
        "$mainUrl/category/semi-barat/" to "Barat Punya",
        "$mainUrl/category/bokep-indo/" to "Indo Punya"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("#gmr-main-load article").mapNotNull { element ->
            val title = element.selectFirst(".entry-title a")?.text() ?: return@mapNotNull null
            val link = element.selectFirst(".entry-title a")?.attr("href") ?: ""
            val image = element.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = image }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            this.plot = document.select(".entry-content p").text()
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
            val serverUrl = fixUrl(server.attr("href"))
            val serverDoc = if (serverUrl == data) document else app.get(serverUrl).document
            val iframe = serverDoc.selectFirst(".gmr-embed-responsive iframe")?.attr("src") ?: continue

            when {
                // SERVER 1: Pyrox (Manual fix)
                iframe.contains("pyrox") -> {
                    val id = iframe.substringAfterLast("/")
                    val host = java.net.URI(iframe).host
                    val res = app.post("https://$host/player/index.php?data=$id&do=getVideo", 
                        headers = mapOf("Referer" to iframe, "X-Requested-With" to "XMLHttpRequest"),
                        data = mapOf("hash" to id, "r" to data)).text
                    
                    Regex("""(https?://[^"'\s]+\.m3u8)""").find(res)?.groupValues?.get(1)?.let { link ->
                        callback.invoke(newExtractorLink("Pyrox", "Server 1", link, ExtractorLinkType.M3U8) { this.referer = iframe })
                    }
                }
                
                // SERVER 2: 4MePlayer (Memanggil file Extractor.kt)
                iframe.contains("4meplayer") -> {
                    FourMePlayerExtractor().getUrl(iframe, data, subtitleCallback, callback)
                }

                // SERVER 3/4: ImaxStreams (Menggunakan WebViewResolver kencang)
                iframe.contains("imaxstreams") -> {
                    val response = app.get(iframe, referer = data, 
                        interceptor = com.lagradost.cloudstream3.network.WebViewResolver(Regex("""\.m3u8""")))
                    if (response.url.contains(".m3u8")) {
                        callback.invoke(newExtractorLink("ImaxStreams", "Server 3/4", response.url, ExtractorLinkType.M3U8) { this.referer = iframe })
                    }
                }
                
                // LAINNYA
                else -> loadExtractor(iframe, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
