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

    // --- KONFIGURASI ---
    // Endpoint diganti ke /info sesuai temuan cURL
    private val RPM_INFO_API = "https://playerngefilm21.rpmlive.online/api/v1/info"
    private val AES_KEY = "kiemtienmua911ca"
    private val AES_IV = "1234567890oiuytr"

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
        val rawHtml = app.get(data).text

        // 1. Ekstrak ID dari RPM Live
        val rpmRegex = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""")
        val rpmMatch = rpmRegex.find(rawHtml)
        
        if (rpmMatch != null) {
            val id = rpmMatch.groupValues[1].ifEmpty { rpmMatch.groupValues[2] }
            if (id.isNotEmpty()) {
                // Gunakan endpoint /info bukan /player
                extractRpmInfo(id, callback)
            }
        }

        // 2. Link Download & Fallback
        val document = org.jsoup.Jsoup.parse(rawHtml)
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            if (!fixedSrc.contains("youtube.com") && !fixedSrc.contains("rpmlive.online")) { 
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractRpmInfo(
        id: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiUrl = "$RPM_INFO_API?id=$id"
            // Header minimalis tapi mirip browser
            val headers = mapOf(
                "Referer" to "https://playerngefilm21.rpmlive.online/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Origin" to "https://playerngefilm21.rpmlive.online"
            )

            // Request ke endpoint info
            val responseBody = app.get(apiUrl, headers = headers).text.trim()
            
            // Response adalah HEX string panjang. Kita harus decode Hex -> Bytes -> Decrypt
            val decrypted = decryptHexAES(responseBody)
            
            if (decrypted.isNotEmpty()) {
                // Parsing hasil dekripsi sebagai JSON
                val json = AppUtils.parseJson<RpmResponse>(decrypted)
                
                // Ambil link video (biasanya di field 'file' atau 'video')
                // Menyesuaikan dengan struktur umum: {"file": "https://...", ...}
                if (!json.file.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "RPM Live",
                            name = "RPM Live (Auto)",
                            url = json.file,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://playerngefilm21.rpmlive.online/"
                            this.quality = getQualityFromName("HD")
                        }
                    )
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class RpmResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    // Fungsi Dekripsi Hex String
    private fun decryptHexAES(hexString: String): String {
        try {
            // 1. Convert Hex String ke Byte Array
            val encryptedBytes = hexStringToByteArray(hexString)

            // 2. Siapkan Key dan IV
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))

            // 3. Dekripsi (AES/CBC/PKCS5Padding)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    // Helper: Hex to Bytes
    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
