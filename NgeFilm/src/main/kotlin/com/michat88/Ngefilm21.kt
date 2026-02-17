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

    // --- KONFIGURASI ---
    private val RPM_PLAYER_API = "https://playerngefilm21.rpmlive.online/api/v1/player"
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

        // 1. Logika RPM Live dengan Token Extraction
        val rpmRegex = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""")
        val rpmMatch = rpmRegex.find(rawHtml)
        
        if (rpmMatch != null) {
            val id = rpmMatch.groupValues[1].ifEmpty { rpmMatch.groupValues[2] }
            if (id.isNotEmpty()) {
                // Kita ambil URL penuh player untuk scraping token
                // Format biasanya: https://playerngefilm21.rpmlive.online/#ID
                val playerUrl = "https://playerngefilm21.rpmlive.online/#$id"
                extractRpmLive(id, playerUrl, data, callback)
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

    private suspend fun extractRpmLive(
        id: String, 
        playerUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // STEP 1: Request Halaman Player untuk cari Token
            val playerPage = app.get(playerUrl, headers = mapOf("Referer" to refererUrl)).text
            
            // Regex mencari token. Pola umum: "token": "..." atau var token = "..."
            // Kita cari pola JSON di dalam script
            var token: String? = null
            
            // Pola 1: Global variable atau JSON key
            val tokenRegex = Regex("""["']token["']\s*:\s*["']([^"']+)["']|var\s+token\s*=\s*["']([^"']+)["']""")
            val match = tokenRegex.find(playerPage)
            if (match != null) {
                token = match.groupValues[1].ifEmpty { match.groupValues[2] }
            }
            
            // Jika token ketemu, kirim di request API
            // Jika tidak, tetap coba request (fallback) siapa tahu token ada di cookie/header lain
            
            val apiUrl = "$RPM_PLAYER_API?id=$id"
            val headers = mutableMapOf(
                "Referer" to playerUrl, // Referer harus dari halaman player itu sendiri
                "Origin" to "https://playerngefilm21.rpmlive.online",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "X-Requested-With" to "XMLHttpRequest"
            )
            
            if (token != null) {
                headers["Authorization"] = "Bearer $token" // Kemungkinan 1: Bearer Token
                // Atau mungkin dikirim sebagai query param? Kita coba tambahkan ke URL juga kalau perlu nanti
            }

            // STEP 2: Request API
            // Kita coba POST dulu karena biasanya API player pakai POST untuk keamanan, kalau gagal baru GET
            // Tapi dari log kamu sebelumnya methodnya GET (HTTP 200), jadi kita pakai GET dulu dengan header tambahan
            
            // Perlu eksperimen: Kadang token dikirim via POST body
            val responseBody = app.get(apiUrl, headers = headers).text
            
            processRpmResponse(responseBody, callback)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun processRpmResponse(responseBody: String, callback: (ExtractorLink) -> Unit) {
        // Coba decode JSON
        try {
            val json = AppUtils.parseJson<RpmResponse>(responseBody)
            
            // Kasus 1: JSON langsung ada file
            if (!json.file.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = "RPM Live",
                        name = "RPM Live (HD)",
                        url = json.file,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://playerngefilm21.rpmlive.online" 
                        this.quality = getQualityFromName("HD")
                    }
                )
                return
            }
            
            // Kasus 2: Field 'data' terenkripsi (AES)
            if (!json.data.isNullOrEmpty()) {
                val decrypted = decryptAES(json.data)
                processDecryptedData(decrypted, callback)
            }
            
        } catch (e: Exception) {
            // Kasus 3: Response bukan JSON, mungkin string terenkripsi langsung
            val decrypted = decryptAES(responseBody)
            processDecryptedData(decrypted, callback)
        }
    }
    
    private suspend fun processDecryptedData(decrypted: String, callback: (ExtractorLink) -> Unit) {
        if (decrypted.isEmpty()) return
        
        if (decrypted.startsWith("http")) {
             callback.invoke(
                newExtractorLink(
                    source = "RPM Live",
                    name = "RPM Live (AES)",
                    url = decrypted,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://playerngefilm21.rpmlive.online"
                    this.quality = getQualityFromName("HD")
                }
            )
        } else if (decrypted.contains("{")) {
             // JSON dalam JSON
             val json = AppUtils.parseJson<RpmResponse>(decrypted)
             if (!json.file.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = "RPM Live",
                        name = "RPM Live (AES-JSON)",
                        url = json.file,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://playerngefilm21.rpmlive.online"
                        this.quality = getQualityFromName("HD")
                    }
                )
             }
        }
    }

    data class RpmResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("data") val data: String? = null
    )

    private fun decryptAES(encrypted: String): String {
        try {
            val cleanEncrypted = encrypted.replace("\n", "").replace("\r", "").trim()
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = Base64.decode(cleanEncrypted, Base64.DEFAULT)
            val decrypted = cipher.doFinal(decodedBytes)
            
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }
}
