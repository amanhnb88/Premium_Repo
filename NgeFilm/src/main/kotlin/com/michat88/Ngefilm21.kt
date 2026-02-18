package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // Header Sakti (Pura-pura jadi Chrome Android)
    private val USER_AGENT_HACK = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // --- HELPER POSTER HD & VALID ---
    private fun Element.getImageAttr(): String? {
        val srcset = this.attr("srcset")
        var url: String? = null
        
        if (srcset.isNotEmpty()) {
            try {
                url = srcset.split(",")
                    .map { it.trim().split(" ") }
                    .filter { it.size >= 2 }
                    .maxByOrNull { it[1].replace("w", "").toIntOrNull() ?: 0 }
                    ?.get(0)
            } catch (e: Exception) { }
        }
        
        if (url.isNullOrEmpty()) {
            url = this.attr("data-src").ifEmpty { this.attr("src") }
        }
        
        return if (!url.isNullOrEmpty()) httpsify(url) else null
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
        if (trailerUrl == null) trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")

        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }
        
        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epNum = Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")
                if (epUrl.isNotEmpty()) newEpisode(epUrl) { this.name = epName; this.episode = epNum } else null
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
                if (trailerUrl != null) this.trailers.add(TrailerData(trailerUrl, null, false))
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ambil semua Tab Player
        val document = app.get(data).document
        val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { it.attr("href") }.toMutableList()
        if (playerLinks.isEmpty()) playerLinks.add(data)

        // 2. Loop setiap Tab
        coroutineScope {
            playerLinks.distinct().map { playerUrl ->
                async {
                    try {
                        val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                        val pageContent = app.get(fixedUrl).text 

                        // [SERVER 4] KRAKENFILES - MANUAL FIX
                        Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""").findAll(pageContent).forEach { match ->
                            extractKrakenManual(match.groupValues[1], callback)
                        }

                        // [SERVER 2] ABYSS (SHORT.ICU) - FIX REDIRECT
                        Regex("""src=["'](https://short\.icu/[^"']+)["']""").findAll(pageContent).forEach { match ->
                            val shortUrl = match.groupValues[1]
                            try {
                                val finalUrl = app.get(shortUrl, headers = mapOf("Referer" to fixedUrl)).url
                                if (finalUrl.contains("abysscdn") || finalUrl.contains("abyss")) {
                                    loadExtractor(finalUrl, subtitleCallback, callback)
                                }
                            } catch (e: Exception) {}
                        }

                        // [SERVER 5] MIXDROP
                        Regex("""src=["'](https://(?:xshotcok\.com|mixdrop\.[a-z]+)/embed-[^"']+)["']""").findAll(pageContent).forEach { match ->
                            loadExtractor(match.groupValues[1], subtitleCallback, callback)
                        }

                        // [SERVER 1] RPM LIVE
                        val rpmMatch = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""").find(pageContent)
                        if (rpmMatch != null) {
                            val id = rpmMatch.groupValues[1].ifEmpty { rpmMatch.groupValues[2] }
                            extractRpm(id, callback)
                        }

                    } catch (e: Exception) {}
                }
            }.awaitAll()
        }
        return true
    }

    // --- MANUAL KRAKEN (ANTI 404) ---
    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf("User-Agent" to USER_AGENT_HACK, "Referer" to mainUrl)
            val text = app.get(url, headers = headers).text
            
            val videoUrl = Regex("""<source[^>]+src=["'](https:[^"']+)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""").find(text)?.groupValues?.get(1)
            
            if (videoUrl != null) {
                // Bersihkan URL
                val cleanUrl = videoUrl.replace("&amp;", "&").replace("\\", "")
                
                callback.invoke(newExtractorLink(
                    source = "Krakenfiles",
                    name = "Krakenfiles",
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    // PENTING: Paksa header User-Agent yang sama ke player!
                    this.headers = mapOf("User-Agent" to USER_AGENT_HACK) 
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {}
    }

    // --- RPM ENCRYPTION ---
    private val AES_KEY = "6b69656d7469656e6d75613931316361"
    private val AES_IV = "313233343536373839306f6975797472"

    private suspend fun extractRpm(id: String, callback: (ExtractorLink) -> Unit) {
        try {
            val h = mapOf("Referer" to "https://playerngefilm21.rpmlive.online/", "Origin" to "https://playerngefilm21.rpmlive.online", "User-Agent" to USER_AGENT_HACK)
            val info = decryptAES(app.get("https://playerngefilm21.rpmlive.online/api/v1/info?id=$id", headers = h).text)
            val pid = Regex(""""playerId"\s*:\s*"([^"]+)"""").find(info)?.groupValues?.get(1) ?: return
            
            val json = "{\"website\":\"new31.ngefilm.site\",\"playing\":true,\"sessionId\":\"${UUID.randomUUID()}\",\"userId\":\"guest\",\"playerId\":\"$pid\",\"videoId\":\"$id\",\"country\":\"ID\",\"platform\":\"Mobile\",\"browser\":\"ChromiumBase\",\"os\":\"Android\"}"
            val token = encryptAES(json)
            
            val hPlayer = h + mapOf("X-Requested-With" to "XMLHttpRequest")
            val res = decryptAES(app.get("https://playerngefilm21.rpmlive.online/api/v1/player?t=$token", headers = hPlayer).text)
            
            Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(res)?.groupValues?.get(1)?.let { m3u8 ->
                callback.invoke(newExtractorLink("RPM Live", "RPM Live", m3u8.replace("\\/", "/"), ExtractorLinkType.M3U8) {
                    this.referer = "https://playerngefilm21.rpmlive.online/"
                    this.quality = Qualities.Unknown.value
                })
            }
        } catch (e: Exception) {}
    }

    private fun encryptAES(text: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexToBytes(AES_KEY), "AES"), IvParameterSpec(hexToBytes(AES_IV)))
        return bytesToHex(cipher.doFinal(text.toByteArray()))
    }
    private fun decryptAES(text: String): String {
        if (text.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(AES_KEY), "AES"), IvParameterSpec(hexToBytes(AES_IV)))
        return String(cipher.doFinal(hexToBytes(text.replace(Regex("[^0-9a-fA-F]"), ""))))
    }
    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        return data
    }
    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
