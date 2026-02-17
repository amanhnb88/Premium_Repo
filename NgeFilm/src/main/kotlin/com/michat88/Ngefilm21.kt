package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    // KUNCI DAN IV HASIL SADAPAN SNIPER
    private val RPM_KEY = "6b69656d7469656e6d75613931316361"
    private val RPM_IV  = "313233343536373839306f6975797472"

    // Helper untuk mengambil gambar (menangani lazy load dan resolusi)
    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty {
            this.attr("src")
        }
        // Menghapus suffix ukuran gambar (misal -152x228) agar dapat kualitas HD
        return url.replace(Regex("-\\d+x\\d+"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(HomePageList("Upload Terbaru", home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst(".content-thumbnail img")?.getImageAttr()
        
        val quality = this.selectFirst(".gmr-quality-item a")?.text() ?: "HD"
        val ratingText = this.selectFirst(".gmr-rating-item")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            addQuality(quality)
            // Menggunakan Score.from10 untuk sistem rating baru
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document

        return document.select("article.item-infinite").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Mengambil Detail Utama
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()

        // 2. Mengambil Genre dan Aktor
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        
        // Map Actor ke ActorData agar sesuai tipe data
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) {
                ActorData(Actor(name, null))
            } else null
        }

        // 3. Mengambil Trailer
        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) {
            trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")
        }

        // 4. Logika TV Series vs Movie
        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }

        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epText = element.text()
                
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")

                if (epUrl.isNotEmpty()) {
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = epNum
                    }
                } else null
            }
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull())
                this.tags = tags
                this.actors = actors
            }
        }

        // FIX TRAILER: Masukkan ke list trailers manual di luar blok builder
        if (trailerUrl != null) {
             response.trailers.add(TrailerData(trailerUrl, null, false))
        }

        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Ekstrak Player Utama (Iframe yang langsung terlihat)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            // LOGIKA KHUSUS RPMLIVE MENGGUNAKAN KUNCI SADAPAN
            if (fixedSrc.contains("rpmlive.online")) {
                val playerId = Regex("#([a-zA-Z0-9]+)").find(fixedSrc)?.groupValues?.get(1)
                if (playerId != null) {
                    try {
                        val apiUrl = "https://playerngefilm21.rpmlive.online/api/v1/video?id=$playerId"
                        val response = app.get(apiUrl, headers = mapOf(
                            "Referer" to "https://playerngefilm21.rpmlive.online/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )).text
                        
                        val decrypted = decryptAES(response, RPM_KEY, RPM_IV)
                        val videoUrl = Regex(""""file":"([^"]+)"""").find(decrypted)?.groupValues?.get(1)
                        
                        if (videoUrl != null) {
                            // PERBAIKAN: Menggunakan newExtractorLink agar tidak deprecated
                            callback.invoke(
                                newExtractorLink(
                                    "RPMLive VIP",
                                    "RPMLive VIP",
                                    videoUrl.replace("\\/", "/")
                                ) {
                                    this.referer = "https://playerngefilm21.rpmlive.online/"
                                    this.quality = Qualities.Unknown.value
                                    this.type = ExtractorLinkType.M3U8
                                }
                            )
                        }
                    } catch (e: Exception) { }
                }
            } else if (!fixedSrc.contains("youtube.com") && !fixedSrc.contains("wp-embedded-content")) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        // 2. Ekstrak Link Download (Bagian bawah player)
        document.select(".gmr-download-list a").forEach { link ->
            val href = link.attr("href")
            loadExtractor(href, subtitleCallback, callback)
        }

        // 3. Ekstrak Server Tambahan (Tab Server 2, Server 3, dst)
        document.select(".muvipro-player-tabs a").forEach { tab ->
            val href = tab.attr("href")
            if (href.contains("?player=") && !tab.hasClass("active")) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                try {
                    val subDoc = app.get(fullUrl).document
                    subDoc.select("iframe").forEach { iframe ->
                        val src = iframe.attr("src")
                        val fixedSrc = if (src.startsWith("//")) "https:$src" else src
                        if (!fixedSrc.contains("youtube.com")) {
                            loadExtractor(fixedSrc, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        return true
    }

    // FUNGSI DEKRIPSI AES UNTUK MEMBONGKAR DATA API
    private fun decryptAES(cipherText: String, keyHex: String, ivHex: String): String {
        return try {
            val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val ivBytes = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val encryptedBytes = cipherText.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val sKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, sKey, ivSpec)
            
            String(cipher.doFinal(encryptedBytes))
        } catch (e: Exception) { "" }
    }
}
