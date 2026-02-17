package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

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

    // --- KONFIGURASI API & ENKRIPSI ---
    private val RPM_BASE_API = "https://playerngefilm21.rpmlive.online/api/v1"
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

        val rpmRegex = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""")
        val rpmMatch = rpmRegex.find(rawHtml)
        
        if (rpmMatch != null) {
            val id = rpmMatch.groupValues[1].ifEmpty { rpmMatch.groupValues[2] }
            if (id.isNotEmpty()) {
                extractRpmGenerator(id, callback)
            }
        }

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

    private suspend fun extractRpmGenerator(
        videoId: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val commonHeaders = mapOf(
                "Referer" to "https://playerngefilm21.rpmlive.online/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
                "Origin" to "https://playerngefilm21.rpmlive.online",
                "Accept" to "*/*"
            )

            // LANGKAH 1: Ambil Info Terenkripsi
            val infoUrl = "$RPM_BASE_API/info?id=$videoId"
            val infoResponse = app.get(infoUrl, headers = commonHeaders)
            val sessionCookies = infoResponse.cookies
            
            val rawInfoHex = infoResponse.text.replace(Regex("[^0-9a-fA-F]"), "")
            val decryptedInfo = decryptHexAES(rawInfoHex)
            
            val infoJson = AppUtils.parseJson<InfoResponse>(decryptedInfo)
            val playerId = infoJson.playerId ?: return 

            // LANGKAH 2: BUAT TOKEN JSON MANUAL (Perbaikan di sini)
            val sessionId = UUID.randomUUID().toString()
            val userId = generateRandomString(4)
            
            // Kita rakit string JSON secara manual untuk menghindari error 'Unresolved reference toJson'
            val jsonString = "{\"website\":\"new31.ngefilm.site\",\"playing\":true,\"sessionId\":\"$sessionId\",\"userId\":\"$userId\",\"playerId\":\"$playerId\",\"videoId\":\"$videoId\",\"country\":\"ID\",\"platform\":\"Mobile\",\"browser\":\"ChromiumBase\",\"os\":\"Android\"}"

            // Enkripsi JSON manual tadi
            val tokenHex = encryptAES(jsonString)

            // LANGKAH 3: Kirim Token Buatan Kita ke /player
            val playerUrl = "$RPM_BASE_API/player?t=$tokenHex"
            val encryptedResponse = app.get(playerUrl, headers = commonHeaders, cookies = sessionCookies).text.replace(Regex("[^0-9a-fA-F]"), "")

            // LANGKAH 4: Dekripsi respons akhir
            val decryptedFinal = decryptHexAES(encryptedResponse)
            
            if (decryptedFinal.isNotEmpty()) {
                var streamUrl = ""
                
                // Coba Parse JSON
                try {
                    val json = AppUtils.parseJson<RpmResponse>(decryptedFinal)
                    streamUrl = json.file ?: json.link ?: json.source ?: ""
                } catch (e: Exception) {}

                // Coba Regex (Backup)
                if (streamUrl.isEmpty()) {
                    val linkRegex = Regex("""(https?:\/\/[^"']+\.m3u8[^"']*)""")
                    val linkMatch = linkRegex.find(decryptedFinal)
                    if (linkMatch != null) {
                        streamUrl = linkMatch.groupValues[1].replace("\\/", "/")
                    }
                }

                if (streamUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "RPM Live",
                            name = "RPM Live (Auto)",
                            url = streamUrl,
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

    data class InfoResponse(
        @JsonProperty("playerId") val playerId: String? = null
    )

    data class RpmResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("source") val source: String? = null
    )

    // Helper: Enkripsi String -> Hex
    private fun encryptAES(plainText: String): String {
        try {
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            return encryptedBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return ""
        }
    }

    // Helper: Dekripsi Hex -> String
    private fun decryptHexAES(hexString: String): String {
        try {
            if (hexString.isEmpty()) return ""
            val encryptedBytes = hexStringToByteArray(hexString)
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        if (len % 2 != 0) return ByteArray(0) 
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    private fun generateRandomString(length: Int): String {
        val allowedChars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }
}
