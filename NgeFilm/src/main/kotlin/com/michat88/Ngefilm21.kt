package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty

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

    // --- KONFIGURASI DEKRIPSI (DARI LOG SADAP) ---
    // Key 1: 1077efecc0b24d02ace33c1e52e2fb4b
    private val RPM_KEY = "1077efecc0b24d02ace33c1e52e2fb4b" 
    // IV: 0123456789abcdef
    private val RPM_IV = "0123456789abcdef" 

    // Helper untuk mengambil gambar
    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty { this.attr("src") }
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

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) ActorData(Actor(name, null)) else null
        }

        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) {
            trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")
        }

        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }

        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epName = element.attr("title").removePrefix("Permalink ke ")
                val epNum = Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()

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

        // 1. CARI PLAYER UTAMA (TERMASUK RPMLIVE)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            // Deteksi RPMLive berdasarkan pola log
            if (fixedSrc.contains("rpmlive.online")) {
                 val playerId = Regex("rpmlive\\.online/.*#([a-zA-Z0-9]+)").find(fixedSrc)?.groupValues?.get(1)
                 if (playerId != null) {
                     extractRpmLive(playerId, subtitleCallback, callback)
                 }
            } else if (!fixedSrc.contains("youtube.com") && !fixedSrc.contains("wp-embedded-content")) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        // 2. Link Download Statis
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        return true
    }

    // --- LOGIKA DEKRIPSI BARU UNTUK RPMLIVE ---
    private suspend fun extractRpmLive(
        id: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // API Endpoint dari log
            val apiUrl = "https://playerngefilm21.rpmlive.online/api/v1/info?id=$id"
            val headers = mapOf("Referer" to "https://playerngefilm21.rpmlive.online/")
            
            val encryptedResponse = app.get(apiUrl, headers = headers).text
            
            // Dekripsi JSON
            val decryptedJson = decryptAes(encryptedResponse, RPM_KEY, RPM_IV)
            
            if (!decryptedJson.isNullOrEmpty()) {
                val data = AppUtils.parseJson<RpmResponse>(decryptedJson)
                
                data.sources?.forEach { source ->
                    val file = source.file ?: return@forEach
                    val label = source.label ?: "Auto"
                    
                    // PERBAIKAN: Menggunakan newExtractorLink menggantikan constructor usang
                    callback.invoke(
                        newExtractorLink(
                            source = "Ngefilm VIP",
                            name = "Ngefilm VIP $label",
                            url = file,
                            referer = "https://playerngefilm21.rpmlive.online/",
                            quality = getQualityFromName(label)
                        )
                    )
                }

                data.tracks?.forEach { track ->
                    if(track.kind == "captions" && !track.file.isNullOrEmpty()) {
                        subtitleCallback.invoke(
                            SubtitleFile(track.label ?: "Indo", track.file)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Fungsi Dekripsi AES (Key Hex String, IV UTF-8 String)
    private fun decryptAes(encrypted: String, keyHex: String, ivString: String): String? {
        return try {
            val keyBytes = hexToBytes(keyHex)
            val ivBytes = ivString.toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            
            // API mengembalikan data dalam format Hex string, bukan Base64 standar
            val encryptedBytes = hexToBytes(encrypted)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // Utilitas Hex to Bytes
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            val j = Integer.parseInt(hex.substring(index, index + 2), 16)
            result[i] = j.toByte()
        }
        return result
    }

    // Data Class untuk parsing JSON hasil dekripsi
    data class RpmResponse(
        @JsonProperty("sources") val sources: List<RpmSource>?,
        @JsonProperty("tracks") val tracks: List<RpmTrack>?
    )

    data class RpmSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?
    )

    data class RpmTrack(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )
}
