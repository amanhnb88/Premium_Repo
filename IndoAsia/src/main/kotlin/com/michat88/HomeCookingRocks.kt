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

                        var m3u8Url = Regex("""(https:\\?/\\?/[^"]+(?:master\.txt|\.m3u8))""")
                            .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                        if (m3u8Url?.endsWith("master.txt") == true) {
                            val txtContent = app.get(m3u8Url, referer = iframeSrc).text
                            val realM3u8 = Regex("""(https?://[^"'\s]+\.m3u8)""").find(txtContent)?.groupValues?.get(1)
                            if (realM3u8 != null) m3u8Url = realM3u8
                        }

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
                                    this.headers = mapOf(
                                        "Origin" to "https://$host",
                                        "Accept" to "*/*"
                                    )
                                }
                            )
                        }
                    } 
                    // ==========================================
                    // SERVER 2: 4MePlayer (GOD MODE BRUTE FORCE)
                    // ==========================================
                    else if (iframeSrc.contains("4meplayer")) {
                        val iframeId = Regex("""(?:id=|/v/|/e/)([\w-]+)""").find(iframeSrc)?.groupValues?.get(1)
                        if (iframeId != null) {
                            val host = java.net.URI(iframeSrc).host
                            val refererWeb = java.net.URI(mainUrl).host
                            val apiUrl = "https://$host/api/v1/video?id=$iframeId&w=360&h=800&r=$refererWeb"

                            // 1. Ambil teks acak dari server
                            val responseHex = app.get(
                                url = apiUrl,
                                headers = mapOf(
                                    "Accept" to "*/*",
                                    "Origin" to "https://$host",
                                    "Referer" to "https://$host/"
                                )
                            ).text.trim()

                            if (responseHex.isNotEmpty()) {
                                // 2. Ubah Hex ke ByteArray
                                val dataBytes = ByteArray(responseHex.length / 2)
                                for (i in dataBytes.indices) {
                                    dataBytes[i] = responseHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                                }
                                val keySpec = javax.crypto.spec.SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
                                var decryptedJson: String? = null

                                // 3. Brute Force Engine murni Kotlin (Hitungan Milidetik)
                                loop@ for (vLen in 0..30) {
                                    val q = vLen * (vLen + 2)
                                    for (vChar in 32..126) {
                                        try {
                                            val ivBytes = ByteArray(16)
                                            var index = 0
                                            for (ke in 1..9) ivBytes[index++] = (ke + q).toByte()
                                            val tt = 111 + vLen
                                            val k = tt + 4
                                            val Me = vChar * 1 - 2
                                            
                                            ivBytes[index++] = q.toByte()
                                            ivBytes[index++] = 111.toByte()
                                            ivBytes[index++] = 0.toByte()
                                            ivBytes[index++] = tt.toByte()
                                            ivBytes[index++] = k.toByte()
                                            ivBytes[index++] = vChar.toByte()
                                            ivBytes[index++] = Me.toByte()

                                            val ivSpec = javax.crypto.spec.IvParameterSpec(ivBytes)
                                            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)
                                            
                                            val resultStr = String(cipher.doFinal())
                                            if (resultStr.contains("m3u8") && resultStr.contains("{")) {
                                                decryptedJson = resultStr
                                                break@loop
                                            }
                                        } catch (e: Exception) {
                                            // Lanjut jika padding salah
                                        }
                                    }
                                }

                                // 4. Bersihkan JSON dan Ekstrak M3U8
                                if (decryptedJson != null) {
                                    val cleanJson = decryptedJson.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else decryptedJson }
                                    
                                    val sourceMatch = Regex(""""source"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                                    val tiktokMatch = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                                    
                                    if (sourceMatch != null) {
                                        callback.invoke(
                                            newExtractorLink(
                                                source = name,
                                                name = "Server 2 (4MePlayer - Normal)",
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
                                                name = "Server 2 (4MePlayer - TikTok HLS)",
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
                    }
                    // ==========================================
                    // SERVER 3 & 4: ImaxStreams
                    // ==========================================
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
                e.printStackTrace()
            }
        }
        return true
    }
}
