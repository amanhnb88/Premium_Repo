package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
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
        return app.get(url).document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingValue = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) ActorData(Actor(name, null)) else null
        }

        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
            ?: document.selectFirst("iframe[src*='youtube.com']")?.attr("src")

        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }

        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epNum = Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                if (epUrl.isNotEmpty()) newEpisode(epUrl) { this.episode = epNum } else null
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingValue?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingValue?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        }
        if (trailerUrl != null) response.trailers.add(TrailerData(trailerUrl, null, false))
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            val fixedSrc = if (src.startsWith("//")) "https:$src" else src
            
            // LOGIKA KHUSUS RPMLIVE (DENGAN PARAMETER LENGKAP)
            if (fixedSrc.contains("rpmlive.online")) {
                val playerId = Regex("#([a-zA-Z0-9]+)").find(fixedSrc)?.groupValues?.get(1)
                if (playerId != null) {
                    try {
                        // Tambahkan parameter w, h, dan r agar server mau memberikan data
                        val apiUrl = "https://playerngefilm21.rpmlive.online/api/v1/video?id=$playerId&w=360&h=800&r=new31.ngefilm.site"
                        
                        val response = app.get(apiUrl, headers = mapOf(
                            "Referer" to "https://playerngefilm21.rpmlive.online/",
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                        )).text
                        
                        val decrypted = decryptAES(response, RPM_KEY, RPM_IV)
                        val videoUrl = Regex(""""file":"([^"]+)"""").find(decrypted)?.groupValues?.get(1)
                        
                        if (videoUrl != null) {
                            // FIX DEPRECATED: Gunakan newExtractorLink
                            callback.invoke(
                                newExtractorLink("RPMLive VIP", "RPMLive VIP", videoUrl.replace("\\/", "/")) {
                                    this.referer = "https://playerngefilm21.rpmlive.online/"
                                    this.type = ExtractorLinkType.M3U8
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    } catch (e: Exception) { }
                }
            } else if (!fixedSrc.contains("youtube.com") && !fixedSrc.contains("wp-embedded-content")) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        document.select(".gmr-download-list a").forEach { loadExtractor(it.attr("href"), subtitleCallback, callback) }
        return true
    }

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
