package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Base64

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

    // --- KONFIGURASI KUNCI DAN API ---
    private val RPM_PLAYER_API = "https://playerngefilm21.rpmlive.online/api/v1/player"
    private val AES_KEY = "kiemtienmua911ca"
    private val AES_IV = "1234567890oiuytr"
    // ---------------------------------

    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty { this.attr("src") }
        return url.replace(Regex("-\\d+x\\d+"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
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
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
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
        // Ambil HTML mentah halaman episode/film
        val rawHtml = app.get(data).text

        // 1. Pencarian Agresif ID RPM Live menggunakan Regex (mirip bash script)
        // Mencari pola: rpmlive.online/...#ID atau ?id=ID
        val rpmRegex = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""")
        val rpmMatch = rpmRegex.find(rawHtml)
        
        if (rpmMatch != null) {
            // Ambil group 1 atau group 2 (karena regex ada OR)
            val id = rpmMatch.groupValues[1].ifEmpty { rpmMatch.groupValues[2] }
            if (id.isNotEmpty()) {
                extractRpmLive(id, data, callback)
            }
        }

        // 2. Link Download (FilePress/Gdrive)
        val document = org.jsoup.Jsoup.parse(rawHtml)
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        // 3. Fallback iframe standar
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            if (!fixedSrc.contains("youtube.com") && !fixedSrc.contains("rpmlive.online")) { 
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractRpmLive(
        id: String, 
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "$RPM_PLAYER_API?id=$id"
            // Header penting agar tidak ditolak
            val headers = mapOf(
                "Referer" to refererUrl,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val responseBody = app.get(apiUrl, headers = headers).text
            
            // Tahap 1: Coba anggap respons adalah JSON biasa
            try {
                val json = AppUtils.parseJson<RpmResponse>(responseBody)
                if (!json.file.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "RPM Live",
                            name = "RPM Live (HD)",
                            url = json.file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName("HD")
                        }
                    )
                    return // Sukses, keluar
                }
            } catch (e: Exception) {
                // Lanjut ke tahap 2 jika gagal parse JSON
            }

            // Tahap 2: Respons mungkin terenkripsi (Ciphertext mentah atau JSON yang membungkus ciphertext)
            // Coba dekripsi responseBody langsung
            val decryptedData = decryptAES(responseBody)
            if (decryptedData.isNotEmpty()) {
                // Cek apakah hasil dekripsi adalah URL valid atau JSON lagi
                if (decryptedData.startsWith("http")) {
                     callback.invoke(
                        newExtractorLink(
                            source = "RPM Live",
                            name = "RPM Live (AES)",
                            url = decryptedData,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName("HD")
                        }
                    )
                } else if (decryptedData.contains("{")) {
                     // Mungkin JSON di dalam JSON
                     val json = AppUtils.parseJson<RpmResponse>(decryptedData)
                     if (!json.file.isNullOrEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "RPM Live",
                                name = "RPM Live (AES-JSON)",
                                url = json.file,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName("HD")
                            }
                        )
                     }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    data class RpmResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("data") val data: String? = null // Jaga-jaga jika ada field data
    )

    private fun decryptAES(encrypted: String): String {
        try {
            // Bersihkan string dari karakter newline atau spasi jika ada
            val cleanEncrypted = encrypted.replace("\n", "").replace("\r", "").trim()
            
            // Jika input terlihat seperti JSON (misal {"data": "..."}), ambil isinya dulu
            var finalPayload = cleanEncrypted
            if (cleanEncrypted.startsWith("{") && cleanEncrypted.contains("data")) {
                 val json = AppUtils.parseJson<RpmResponse>(cleanEncrypted)
                 finalPayload = json.data ?: cleanEncrypted
            }

            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            // Decode Base64 Android
            val decodedBytes = Base64.decode(finalPayload, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decodedBytes)
            
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            // Logging error dekripsi bisa ditambahkan di sini
            return ""
        }
    }
}
