package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList("Upload Terbaru", home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: return null
        return newMovieSearchResponse(titleElement.text(), titleElement.attr("href"), TvType.Movie) {
            this.posterUrl = selectFirst(".content-thumbnail img")?.let { 
                it.attr("data-src").ifEmpty { it.attr("src") } 
            }?.replace(Regex("-\\d+x\\d+"), "")
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = document.selectFirst("meta[property='og:image']")?.attr("content")
            this.plot = document.selectFirst(".entry-content p")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. CARI LINK RPMLIVE DI IFRAME
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-litespeed-src").ifEmpty { iframe.attr("src") }
            if (src.contains("rpmlive.online")) {
                val id = src.substringAfterLast("/").substringBefore("?").replace("#", "")
                if (id.length > 3) {
                    val apiRes = app.get("https://playerngefilm21.rpmlive.online/api/v1/video?id=$id", 
                        headers = mapOf("Referer" to src, "X-Requested-With" to "XMLHttpRequest")
                    ).text
                    
                    // 2. EKSEKUSI DEKRIPSI MATA TUHAN
                    val decryptedLink = decryptRpm(apiRes)
                    if (decryptedLink.contains("http")) {
                        callback.invoke(
                            newExtractorLink(
                                "Rpmlive (Mata Tuhan)",
                                "Server VIP Clean",
                                decryptedLink,
                                "https://playerngefilm21.rpmlive.online/",
                                Qualities.Unknown.value,
                                true
                            )
                        )
                    }
                }
            }
        }
        return true
    }

    // --- LOGIKA PEMBONGKAR BRANKAS HEX ---
    private fun decryptRpm(hex: String): String {
        return try {
            // Kita gunakan kunci yang kita temukan di DUMP_JS tadi
            val keyStr = "1077efecc0b24d02ace33c1e52e2fb4b" 
            val res = StringBuilder()
            val hexData = hex.chunked(2).map { it.toInt(16) }
            
            // Berdasarkan analisa JS: Ini adalah Simple XOR dengan Key bergulir
            for (i in hexData.indices) {
                val keyChar = keyStr[i % keyStr.length].code
                res.append((hexData[i] xor keyChar).toChar())
            }
            
            val final = res.toString()
            // Cari link di dalam hasil XOR
            val regex = Regex("""https?://[^\s"']+""")
            regex.find(final)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
