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
            try {
                val serverUrl = fixUrl(server.attr("href"))
                val serverDoc = if (serverUrl == data) document else app.get(serverUrl).document
                val iframeSrc = serverDoc.selectFirst(".gmr-embed-responsive iframe")?.attr("src")

                if (iframeSrc != null) {
                    if (iframeSrc.contains("embedpyrox") || iframeSrc.contains("pyrox")) {
                        val iframeId = iframeSrc.substringAfterLast("/")
                        val host = java.net.URI(iframeSrc).host
                        val apiUrl = "https://$host/player/index.php?data=$iframeId&do=getVideo"

                        val response = app.post(
                            url = apiUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to iframeSrc),
                            data = mapOf("hash" to iframeId, "r" to data)
                        ).text

                        var m3u8Url = Regex("""(https:\\?/\\?/[^"]+(?:master\.txt|\.m3u8))""")
                            .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                        if (m3u8Url?.endsWith("master.txt") == true) {
                            val txtContent = app.get(m3u8Url, referer = iframeSrc).text
                            val realM3u8 = Regex("""(https?://[^"'\s]+\.m3u8)""").find(txtContent)?.groupValues?.get(1)
                            if (realM3u8 != null) m3u8Url = realM3u8
                        }

                        if (m3u8Url != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "Server 1 (Pyrox)",
                                    url = m3u8Url,
                                    referer = iframeSrc,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        }
                    } 
                    // ==========================================
                    // MEMANGGIL CLASS EXTRACTOR SERVER 2
                    // ==========================================
                    else if (iframeSrc.contains("4meplayer")) {
                        FourMePlayerExtractor().getUrl(iframeSrc, data, subtitleCallback, callback)
                    }
                    else if (iframeSrc.contains("imaxstreams")) {
                        val iframeHtml = app.get(iframeSrc, referer = data).text
                        var pageText = iframeHtml
                        
                        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,.*?\)\)""")
                        packedRegex.findAll(pageText).forEach { match ->
                            val unpacked = JsUnpacker(match.value).unpack()
                            if (!unpacked.isNullOrEmpty()) {
                                pageText += "\n$unpacked" 
                            }
                        }

                        var m3u8Url = Regex("""(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(pageText)?.groupValues?.get(1)
                            ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(pageText)?.groupValues?.get(1)

                        if (m3u8Url == null) {
                            val fileCode = Regex("""/e/([^/]+)|/embed/([^/]+)""").find(iframeSrc)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                            val hash = Regex("""["']?hash["']?\s*[:=]\s*["']([^"']+)["']""").find(pageText)?.groupValues?.get(1)
                            
                            if (fileCode != null && hash != null) {
                                val host = java.net.URI(iframeSrc).host
                                val webHost = java.net.URI(mainUrl).host
                                val ajaxUrl = "https://$host/dl?op=view&file_code=$fileCode&hash=$hash&embed=1&referer=$webHost&adb=1&hls4=1"
                                
                                val ajaxResponse = app.get(
                                    url = ajaxUrl, 
                                    headers = mapOf("Referer" to iframeSrc, "X-Requested-With" to "XMLHttpRequest")
                                ).text
                                
                                m3u8Url = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(ajaxResponse)?.groupValues?.get(1)
                            }
                        }

                        if (m3u8Url != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "Server 3/4 (ImaxStreams)",
                                    url = m3u8Url,
                                    referer = iframeSrc,
                                    quality = Qualities.Unknown.value,
                                    type = ExtractorLinkType.M3U8
                                )
                            )
                        } else {
                            loadExtractor(iframeSrc, data, subtitleCallback, callback)
                        }
                    }
                    else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }
}
