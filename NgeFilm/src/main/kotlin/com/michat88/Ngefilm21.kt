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

    // --- KONFIGURASI HASIL SNIPER ---
    private val UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val RPM_KEY = "6b69656d7469656e6d75613931316361" 
    private val RPM_IV  = "313233343536373839306f6975797472"

    private fun Element.getImageAttr(): String? {
        var url = this.attr("data-src").ifEmpty { this.attr("src") }
        if (url.isEmpty()) {
            val srcset = this.attr("srcset")
            if (srcset.isNotEmpty()) {
                url = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
            }
        }
        return if (url.isNotEmpty()) {
            // Hapus dimensi gambar untuk dapat HD (cth: -60x90)
            httpsify(url).replace(Regex("-\\d+x\\d+"), "")
        } else null
    }

    // --- DAFTAR KATEGORI ---
    private val categories = listOf(
        Pair("Upload Terbaru", ""), 
        Pair("Indonesia Movie", "/country/indonesia"),
        Pair("Indonesia Series", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=&movieyear=&country=indonesia&quality="),
        Pair("Drakor", "/?s=&search=advanced&post_type=tv&index=&orderby=&genre=drama&movieyear=&country=korea&quality="),
        Pair("VivaMax", "/country/philippines"),
        Pair("Movies", "/country/canada"),
        Pair("Ahok Movie", "/country/china")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeItems = coroutineScope {
            categories.map { (title, urlPath) ->
                async {
                    val finalUrl = if (urlPath.isEmpty()) {
                        "$mainUrl/page/$page/"
                    } else if (urlPath.contains("?")) {
                        val split = urlPath.split("?")
                        "$mainUrl/page/$page/?${split[1]}"
                    } else {
                        "$mainUrl$urlPath/page/$page/"
                    }

                    try {
                        val document = app.get(finalUrl).document
                        val items = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
                        
                        if (items.isNotEmpty()) {
                            HomePageList(title, items)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return newHomePageResponse(homeItems, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: ""
        
        // FIX QUALITY BADGE: Ambil teks langsung dari div pembungkusnya
        val qualityText = this.selectFirst(".gmr-quality-item")?.text()?.trim() ?: "HD"
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = this@toSearchResult.selectFirst(".content-thumbnail img")?.getImageAttr()
            addQuality(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv").document
            .select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        
        // FIX SINOPSIS & INFO EXTRA
        val plotText = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim() 
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
        val yearText = document.selectFirst(".gmr-moviedata a[href*='year']")?.text()?.toIntOrNull()
        
        // FIX RATING (Jangan pakai .toRatingInt())
        val ratingText = document.selectFirst("[itemprop='ratingValue']")?.text()?.trim()
        
        val tagsList = document.select(".gmr-moviedata a[href*='genre']").map { it.text() }
        val actorsList = document.select("[itemprop='actors'] a").map { it.text() }
        val trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val epElements = document.select(".gmr-listseries a").filter { it.attr("href").contains("/eps/") }
        val isSeries = epElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = epElements.mapNotNull { 
                newEpisode(it.attr("href")) { 
                    this.name = it.attr("title").removePrefix("Permalink ke ")
                    this.episode = Regex("(\\d+)").find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                // FIX RATING: Pakai Score.from10
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                // FIX ACTORS: Map string ke ActorData
                this.actors = actorsList.map { ActorData(Actor(it)) }
                // FIX TRAILER: Add manual ke list
                if (!trailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(trailerUrl, null, false))
                }
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) { 
                this.posterUrl = poster
                this.plot = plotText
                this.year = yearText
                // FIX RATING: Pakai Score.from10
                this.score = Score.from10(ratingText)
                this.tags = tagsList
                // FIX ACTORS: Map string ke ActorData
                this.actors = actorsList.map { ActorData(Actor(it)) }
                // FIX TRAILER: Add manual ke list
                if (!trailerUrl.isNullOrEmpty()) {
                    this.trailers.add(TrailerData(trailerUrl, null, false))
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { it.attr("href") }.toMutableList()
        if (playerLinks.isEmpty()) playerLinks.add(data)

        coroutineScope {
            playerLinks.distinct().map { playerUrl ->
                async {
                    try {
                        val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                        val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER)).text 

                        // [SERVER 1] RPM LIVE
                        val rpmMatch = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""").find(pageContent)
                        rpmMatch?.let { extractRpm(it.groupValues[1].ifEmpty { it.groupValues[2] }, callback) }

                        // [SERVER 4] KRAKENFILES
                        Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""").findAll(pageContent).forEach { 
                            extractKrakenManual(it.groupValues[1], callback) 
                        }

                        // [SERVER 2] ABYSS
                        Regex("""src=["'](https://short\.icu/[^"']+)["']""").findAll(pageContent).forEach { 
                            val finalUrl = app.get(it.groupValues[1], headers = mapOf("Referer" to fixedUrl)).url
                            if (finalUrl.contains("abyss")) loadExtractor(finalUrl, subtitleCallback, callback)
                        }

                        // [SERVER 5] MIXDROP
                        Regex("""src=["'](https://(?:xshotcok\.com|mixdrop\.[a-z]+)/embed-[^"']+)["']""").findAll(pageContent).forEach { 
                            loadExtractor(it.groupValues[1], subtitleCallback, callback)
                        }
                    } catch (e: Exception) {}
                }
            }.awaitAll()
        }
        return true
    }

    private suspend fun extractRpm(id: String, callback: (ExtractorLink) -> Unit) {
        try {
            val h = mapOf("Referer" to "https://playerngefilm21.rpmlive.online/", "X-Requested-With" to "XMLHttpRequest", "User-Agent" to UA_BROWSER)
            val infoDec = decryptAES(app.get("https://playerngefilm21.rpmlive.online/api/v1/info?id=$id", headers = h).text)
            val pid = Regex(""""playerId"\s*:\s*"([^"]+)"""").find(infoDec)?.groupValues?.get(1) ?: return
            
            val jsonPayload = "{\"website\":\"new31.ngefilm.site\",\"playing\":true,\"sessionId\":\"${UUID.randomUUID()}\",\"userId\":\"guest\",\"playerId\":\"$pid\",\"videoId\":\"$id\",\"country\":\"ID\",\"platform\":\"Mobile\",\"browser\":\"ChromiumBase\",\"os\":\"Android\"}"
            val token = encryptAES(jsonPayload)
            val playerDec = decryptAES(app.get("https://playerngefilm21.rpmlive.online/api/v1/player?t=$token", headers = h).text)
            
            Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(playerDec)?.groupValues?.get(1)?.let { m3u8 ->
                callback.invoke(newExtractorLink("RPM Live", "RPM Live", m3u8.replace("\\/", "/"), ExtractorLinkType.M3U8) {
                    this.referer = "https://playerngefilm21.rpmlive.online/"
                })
            }
        } catch (e: Exception) {}
    }

    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl)).text
            val videoUrl = Regex("""<source[^>]+src=["'](https:[^"']+)["']""").find(text)?.groupValues?.get(1)
                ?: Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""").find(text)?.groupValues?.get(1)
            
            videoUrl?.let { clean ->
                callback.invoke(newExtractorLink("Krakenfiles", "Krakenfiles", clean.replace("&amp;", "&").replace("\\", ""), ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.headers = mapOf("User-Agent" to UA_BROWSER) 
                })
            }
        } catch (e: Exception) {}
    }

    // --- AES ENGINE ---
    private fun encryptAES(text: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexToBytes(RPM_KEY), "AES"), IvParameterSpec(hexToBytes(RPM_IV)))
        return bytesToHex(cipher.doFinal(text.toByteArray()))
    }
    
    private fun decryptAES(text: String): String {
        if (text.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(RPM_KEY), "AES"), IvParameterSpec(hexToBytes(RPM_IV)))
            String(cipher.doFinal(hexToBytes(text.replace(Regex("[^0-9a-fA-F]"), ""))))
        } catch (e: Exception) { "" }
    }
    
    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        return data
    }
    
    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
