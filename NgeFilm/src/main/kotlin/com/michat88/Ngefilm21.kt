package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
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

    // --- KONFIGURASI BARU DARI SCREENSHOT ---
    private val RPM_PLAYER_API = "https://playerngefilm21.rpmlive.online/api/v1/player"
    // Kunci hasil decode dari Hex: 6b69656d7469656e6d75613931316361
    private val AES_KEY = "kiemtienmua911ca"
    // IV hasil decode dari Hex: 313233343536373839306f6975797472
    private val AES_IV = "1234567890oiuytr"
    // ----------------------------------------

    // Helper untuk mengambil gambar (menangani lazy load dan resolusi)
    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty {
            this.attr("src")
        }
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

        // 1. Cari Player RPM Live (Prioritas Utama sesuai Screenshot)
        document.select("iframe[src*='rpmlive.online']").forEach { iframe ->
            val src = iframe.attr("src")
            val id = if (src.contains("?id=")) {
                src.substringAfter("?id=").substringBefore("&")
            } else if (src.contains("#")) {
                src.substringAfter("#")
            } else {
                null
            }

            if (id != null) {
                extractRpmLive(id, subtitleCallback, callback)
            }
        }

        // 2. Fallback: Ekstrak Player Lain (Iframe standar)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            if (!fixedSrc.contains("youtube.com") && 
                !fixedSrc.contains("wp-embedded-content") && 
                !fixedSrc.contains("rpmlive.online")) { 
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        // 3. Link Download
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        return true
    }

    // --- LOGIKA BARU UNTUK RPM LIVE ---
    private suspend fun extractRpmLive(
        id: String, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "$RPM_PLAYER_API?id=$id"
            val response = app.get(apiUrl, referer = "$mainUrl/").text
            
            val json = AppUtils.parseJson<RpmResponse>(response)
            
            // PERBAIKAN DI SINI: Menggunakan newExtractorLink
            if (!json.file.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = "RPM Live",
                        name = "RPM Live (Auto)",
                        url = json.file,
                        type = ExtractorLinkType.M3U8 // Tentukan tipe M3U8 di sini
                    ) {
                        this.referer = "" // Atau "$mainUrl/" jika dibutuhkan
                        this.quality = getQualityFromName("HD")
                    }
                )
            } 
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    data class RpmResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    private fun decryptAES(encrypted: String): String {
        try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray())
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
            val decrypted = cipher.doFinal(decodedBytes)
            
            return String(decrypted)
        } catch (e: Exception) {
            return ""
        }
    }
}
