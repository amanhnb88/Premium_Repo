package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HomeCookingRocks : MainAPI() {
    
    // 1. Mengatur informasi dasar plugin
    override var name = "Home Cooking Rocks" // Nama yang akan muncul di aplikasi
    override var mainUrl = "https://homecookingrocks.com" // URL utama situs
    override var supportedTypes = setOf(TvType.Others, TvType.Movie) 
    override var lang = "id" // Bahasa (Karena isinya subtitle Indonesia)
    override val hasMainPage = true // Menandakan bahwa plugin ini punya halaman utama
    
    // 2. Mendefinisikan kategori halaman depan (Sudah dirapikan sesuai permintaanmu)
    override val mainPage = mainPageOf(
        "$mainUrl/category/asia-m/" to "Asia",
        "$mainUrl/category/vivamax/" to "VivaMax",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/kelas-bintang/" to "Kelas Bintang",
        "$mainUrl/category/semi-barat/" to "Barat Punya",
        "$mainUrl/category/bokep-indo/" to "Indo Punya",
        "$mainUrl/category/bokep-vietnam/" to "Vietnam Punya"
    )

    // 3. Fungsi untuk mengambil daftar film di halaman utama (Mendukung Infinite Scroll / Halaman 2,3, dst)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        // Logika untuk halaman (Paginasi)
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

    // 4. Fungsi untuk mencari film
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

    // 5. Fungsi untuk memuat detail informasi film (Gambar kualitas HD, Sinopsis, Rating, dll)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".entry-content p").joinToString("\n") { it.text() }.trim()

        val year = document.selectFirst(".gmr-moviedata:contains(Tahun:) a")?.text()?.toIntOrNull()
        val ratingString = document.selectFirst(".gmr-meta-rating span[itemprop=ratingValue]")?.text()
        
        val tags = document.select(".gmr-moviedata:contains(Genre:) a").map { it.text() }
        val actors = document.select(".gmr-moviedata:contains(Pemain:) a").map { it.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(ratingString)
            addActors(actors)
        }
    }

    // 6. Fungsi "Pamungkas" untuk menarik tautan video asli dari berbagai jenis server
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
                // LOGIKA SERVER 1: Pyrox
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
                        data = mapOf(
                            "hash" to iframeId,
                            "r" to data
                        )
                    ).text

                    val m3u8Url = Regex("""(https:\\?/\\?/[^"]+(?:master\.txt|\.m3u8))""")
                        .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                    if (m3u8Url != null) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "Server 1 (Pyrox)",
                                url = m3u8Url,
                                referer = iframeSrc,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    }
                } 
                // ==========================================
                // LOGIKA SERVER 2: 4MePlayer
                // ==========================================
                else if (iframeSrc.contains("4meplayer")) {
                    val idMatch = Regex("""(?:id=|/v/|/e/)([\w-]+)""").find(iframeSrc)
                    val id = idMatch?.groupValues?.get(1)

                    if (id != null) {
                        val host = java.net.URI(iframeSrc).host
                        val webHost = java.net.URI(mainUrl).host
                        val apiUrl = "https://$host/api/v1/video?id=$id&r=$webHost"

                        val response = app.get(
                            url = apiUrl,
                            headers = mapOf("Referer" to iframeSrc)
                        ).text

                        val m3u8Url = Regex("""(https:\\?/\\?/[^"]+\.m3u8[^"]*)""")
                            .find(response)?.groupValues?.get(1)?.replace("\\/", "/")

                        if (m3u8Url != null) {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "Server 2 (4MePlayer)",
                                    url = m3u8Url,
                                    referer = iframeSrc,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true
                                )
                            )
                        }
                    }
                }
                // ==========================================
                // LOGIKA SERVER 3 & 4: ImaxStreams (.com dan .net)
                // ==========================================
                else if (iframeSrc.contains("imaxstreams")) {
                    val iframeHtml = app.get(iframeSrc, referer = data).text
                    
                    // Cara 1: Coba cari langsung link .m3u8 dari dalam HTML
                    var m3u8Url = Regex("""(?:file|src)["']?\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(iframeHtml)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(iframeHtml)?.groupValues?.get(1)

                    // Cara 2: Jika disembunyikan pakai AJAX, curi token (hash) nya dan panggil API mereka
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
                            ExtractorLink(
                                source = name,
                                name = "Server 3/4 (ImaxStreams)",
                                url = m3u8Url,
                                referer = iframeSrc,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true
                            )
                        )
                    } else {
                        // Jika gagal semua, lempar ke Cloudstream
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    }
                }
                // ==========================================
                // LOGIKA DEFAULT: Server Eksternal Lain (YouTube, DoodStream, dll)
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
