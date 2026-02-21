package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        // 1. Ambil raw HTML sebagai String (Bypass Jsoup document agar jauh lebih ringan)
        val html = app.get(data).text

        // 2. Parsing dengan Regex: Cari blok tabs dan ekstrak URL di dalamnya
        val tabsBlockMatch = Regex("""class=["'][^"']*muvipro-player-tabs[^"']*["'][^>]*>(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(html)
        
        val rawServerUrls = if (tabsBlockMatch != null) {
            Regex("""href=["']([^"']+)["']""").findAll(tabsBlockMatch.groupValues[1])
                .map { fixUrl(it.groupValues[1]) }
                .distinct()
                .toList()
        } else {
            listOf(data) // Fallback jika tidak ada tabs (hanya 1 server)
        }

        // 3. Prioritization: Urutkan URL. URL halaman saat ini (data) dikerjakan pertama (index 0)
        // karena kita sudah punya text HTML-nya (variabel html), menghemat 1x request network!
        val sortedUrls = rawServerUrls.sortedBy { url ->
            if (url == data) 0 else 1
        }

        // 4. Structured Concurrency: Jalankan semua proses ekstraksi secara paralel!
        coroutineScope {
            sortedUrls.forEach { serverUrl ->
                launch(Dispatchers.IO) {
                    try {
                        // Jika URL adalah halaman saat ini, pakai 'html' yang sudah ada. Jika beda, get HTML baru.
                        val serverHtml = if (serverUrl == data) html else app.get(serverUrl).text
                        
                        // Ekstrak iframe SRC menggunakan Regex murni
                        val iframeMatch = Regex("""class=["'][^"']*gmr-embed-responsive[^"']*["'][^>]*>.*?<iframe[^>]+src=["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL).find(serverHtml)
                        val iframeSrc = iframeMatch?.groupValues?.get(1)

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

                                val m3u8Url = Regex("""(https:\\?\/\\?\/[^"]+(?:master\.txt|\.m3u8))""")
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
                                            this.headers = mapOf(
                                                "Origin" to "https://$host",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                }
                            } 
                            // ==========================================
                            // SERVER 2: 4MePlayer (Bypass Enkripsi AES)
                            // ==========================================
                            else if (iframeSrc.contains("4meplayer")) {
                                val videoId = iframeSrc.substringAfterLast("#")
                                if (videoId.isNotEmpty() && videoId != iframeSrc) {
                                    val host = java.net.URI(iframeSrc).host
                                    
                                    // Skenario aman: Coba 2 endpoint andalan mereka
                                    val endpoints = listOf(
                                        "https://$host/api/v1/video?id=$videoId",
                                        "https://$host/api/v1/info?id=$videoId"
                                    )
                                    
                                    var foundM3u8 = false
                                    for (apiUrl in endpoints) {
                                        if (foundM3u8) break // Berhenti jika M3U8 sudah ketemu
                                        
                                        try {
                                            val hexResponse = app.get(apiUrl, referer = iframeSrc).text.trim()
                                            
                                            // Pastikan response adalah hex murni sebelum didekripsi
                                            if (hexResponse.isNotEmpty() && hexResponse.matches(Regex("^[0-9a-fA-F]+$"))) {
                                                
                                                // Kunci Rahasia dan IV dari hasil Reverse Engineering
                                                val secretKey = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
                                                val ivBytes = ByteArray(16)
                                                for (i in 0..8) ivBytes[i] = i.toByte() 
                                                for (i in 9..15) ivBytes[i] = 32.toByte() 
                                                
                                                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                                                val secretKeySpec = SecretKeySpec(secretKey, "AES")
                                                val ivParameterSpec = IvParameterSpec(ivBytes)
                                                
                                                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
                                                
                                                // Konversi string Hex ke ByteArray
                                                val decodedHex = hexResponse.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                                val decryptedBytes = cipher.doFinal(decodedHex)
                                                val decryptedText = String(decryptedBytes, Charsets.UTF_8)
                                                
                                                // Curi link M3U8 via Regex
                                                val m3u8Regex = """"([^"]+\.m3u8[^"]*)"""".toRegex()
                                                val match = m3u8Regex.find(decryptedText)
                                                
                                                if (match != null) {
                                                    var m3u8Url = match.groupValues[1].replace("\\/", "/")
                                                    if (m3u8Url.startsWith("/")) {
                                                        m3u8Url = "https://$host$m3u8Url"
                                                    }
                                                    
                                                    callback.invoke(
                                                        newExtractorLink(
                                                            source = name,
                                                            name = "Server 2 (4MePlayer)",
                                                            url = m3u8Url,
                                                            type = ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = iframeSrc
                                                            this.quality = Qualities.Unknown.value
                                                        }
                                                    )
                                                    foundM3u8 = true
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // Jika gagal dekripsi / error 404, loop akan lanjut ke endpoint berikutnya
                                        }
                                    }
                                }
                            }
                            // ==========================================
                            // SERVER 3: ImaxStreams
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
                                }
                            }
                            // ==========================================
                            // DEFAULT: Ekstraktor Bawaan CloudStream
                            // ==========================================
                            else {
                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        // Jika satu tab/server gagal (misal server mati), biarkan coroutine lain tetap berjalan
                        e.printStackTrace()
                    }
                }
            }
        }
        // Semua coroutine (launch) ditunggu sampai selesai di dalam coroutineScope, 
        // sehingga fungsi tidak akan return true sebelum semua server dicek.
        return true
    }
}
