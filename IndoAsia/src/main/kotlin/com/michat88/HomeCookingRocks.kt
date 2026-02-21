package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HomeCookingRocks : MainAPI() {
    
    // 1. Mengatur informasi dasar plugin
    override var name = "Home Cooking Rocks"
    override var mainUrl = "https://homecookingrocks.com"
    override var supportedTypes = setOf(TvType.Others, TvType.Movie) 
    override var lang = "id"
    override val hasMainPage = true
    
    // 2. Kategori Halaman Depan
    override val mainPage = mainPageOf(
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Kelas Bintang",
        "$mainUrl/category/semi-barat/" to "Barat Punya",
        "$mainUrl/category/bokep-indo/" to "Indo Punya",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam Punya"
    )

    // 3. Mengambil Halaman Utama
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }
        
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

    // 4. Pencarian
    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        val elements = document.select("#gmr-main-load article")
        
        return elements.mapNotNull { element ->
            val titleElement = element.selectFirst(".entry-title a, h2 a")
            val title = titleElement?.text() ?: return@mapNotNull null
            val url = titleElement.attr("href")
            val image = element.selectFirst("img")?.attr("src")
            
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = image
            }
        }
    }

    // 5. Load Detail
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

    // 6. Load Links
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
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to iframeSrc
                        ),
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
                            }
                        )
                    }
                } 
                // ==========================================
                // SERVER 2: 4MePlayer (Diperbaiki menggunakan loadExtractor)
                // ==========================================
                else if (iframeSrc.contains("4meplayer")) {
                    // Kita biarkan CloudStream yang membongkar enkripsi dinamisnya
                    loadExtractor(
                        url = iframeSrc,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
                // ==========================================
                // SERVER 3 & 4: ImaxStreams (.com dan .net)
                // ==========================================
                else if (iframeSrc.contains("imaxstreams")) {
                    val iframeHtml = app.get(iframeSrc, referer = data).text
                    
                    var m3u8Url = Regex("""(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(iframeHtml)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)

                    if (m3u8Url == null) {
                        val fileCode = Regex("""/e/([^/]+)|/embed/([^/]+)""").find(iframeSrc)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                        val hash = Regex("""["']?hash["']?\s*[:=]\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                        
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
                            newExtractorLink(
                                source = name,
                                name = "Server 3/4 (ImaxStreams)",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeSrc
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    } else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
                // ==========================================
                // DEFAULT: Server Eksternal Lain
                // ==========================================
                else {
                    loadExtractor(
                        url = iframeSrc,
                        referer = data,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        }
        return true
    }
}
