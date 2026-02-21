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
                                    // Header wajib agar ExoPlayer tidak diblokir
                                    this.headers = mapOf(
                                        "Origin" to "https://$host",
                                        "Referer" to iframeSrc,
                                        "Accept" to "*/*"
                                    )
                                }
                            )
                        }
                    } 
                    // ==========================================
                    // SERVER 2: 4MePlayer (GOD MODE)
                    // ==========================================
                    else if (iframeSrc.contains("4meplayer")) {
                        val iframeId = Regex("""(?:id=|/v/|/e/)([\w-]+)""").find(iframeSrc)?.groupValues?.get(1)
                        if (iframeId != null) {
                            val host = java.net.URI(iframeSrc).host
                            val refererWeb = java.net.URI(mainUrl).host
                            val apiUrl = "https://$host/api/v1/video?id=$iframeId&w=360&h=800&r=$refererWeb"

                            val responseHex = app.get(
                                url = apiUrl,
                                headers = mapOf(
                                    "Accept" to "*/*",
                                    "Origin" to "https://$host",
                                    "Referer" to "https://$host/"
                                )
                            ).text.trim()

                            if (responseHex.isNotEmpty()) {
                                // Eksekusi Sandi Statis hasil bongkaran Python
                                val dataBytes = responseHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                val keySpec = javax.crypto.spec.SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
                                
                                // IV statis: 010203040506070809006f006f73201e
                                val ivBytes = byteArrayOf(
                                    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x00,
                                    0x6f, 0x00, 0x6f, 0x73, 0x20, 0x1e
                                )
                                val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
                                
                                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
                                
                                val decryptedJson = String(cipher.doFinal())
                                val cleanJson = decryptedJson.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else decryptedJson }
                                
                                val sourceMatch = Regex(""""source"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                                val tiktokMatch = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                                
                                if (sourceMatch != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "Server 2 (4MePlayer)",
                                            url = sourceMatch,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                                if (tiktokMatch != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "Server 2 (TikTok HLS)",
                                            url = if (tiktokMatch.startsWith("http")) tiktokMatch else "https://$host$tiktokMatch",
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = iframeSrc
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                        }
                    }
                    // ==========================================
                    // SERVER 3 & 4: ImaxStreams (Jalur Cepat WebViewResolver)
                    // ==========================================
                    else if (iframeSrc.contains("imaxstreams")) {
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
                    // DEFAULT: Server Lainnya
                    // ==========================================
                    else {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Jangan crash, lompat ke server berikutnya
                e.printStackTrace()
            }
        }
        return true
    }
}
