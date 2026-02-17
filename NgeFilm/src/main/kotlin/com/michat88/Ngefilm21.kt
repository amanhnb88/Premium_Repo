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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // KUNCI HASIL SADAPAN "SNIPER"
    private val NEW_RPM_KEY = "6b69656d7469656e6d75613931316361"
    private val NEW_RPM_IV  = "313233343536373839306f6975797472"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse("Update Terbaru", home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.ifEmpty { this.selectFirst("img")?.attr("src") }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".gmr-movie-data img")?.attr("src")
        return newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val iframe = document.selectFirst("iframe[src*='rpmlive.online']") ?: return false
        val src = iframe.attr("src")
        val playerId = Regex("#([a-zA-Z0-9]+)").find(src)?.groupValues?.get(1) ?: return false

        // 1. Panggil API Video Terbaru
        val apiUrl = "https://playerngefilm21.rpmlive.online/api/v1/video?id=$playerId"
        val response = app.get(apiUrl, headers = mapOf(
            "Referer" to "https://playerngefilm21.rpmlive.online/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )).text

        // 2. Dekripsi Data menggunakan Kunci SNIPER
        val decrypted = decryptAES(response, NEW_RPM_KEY, NEW_RPM_IV)
        
        if (decrypted.contains("master.m3u8")) {
            // Ekstrak link dari JSON hasil dekripsi
            val videoLink = Regex(""""file":"([^"]+)"""").find(decrypted)?.groupValues?.get(1)
            if (videoLink != null) {
                callback.invoke(
                    ExtractorLink(
                        "RPMLive VIP",
                        "RPMLive VIP",
                        videoLink.replace("\\/", "/"),
                        "https://playerngefilm21.rpmlive.online/",
                        Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        }
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
