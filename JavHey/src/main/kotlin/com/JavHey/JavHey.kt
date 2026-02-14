package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class JavHey : MainAPI() {
    override var mainUrl = "https://javhey.com"
    override var name = "JAVHEY"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Header disesuaikan dengan trafik asli (Chrome Android) agar tidak terdeteksi sebagai Bot
    private val headers = mapOf(
        "Authority" to "javhey.com",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/paling-baru/page=" to "Paling Baru",
        "$mainUrl/videos/paling-dilihat/page=" to "Paling Dilihat",
        "$mainUrl/videos/top-rating/page=" to "Top Rating",
        "$mainUrl/videos/jav-sub-indo/page=" to "JAV Sub Indo"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page == 1) {
            request.data.replace("/page=", "")
        } else {
            request.data + page
        }

        val document = app.get(url, headers = headers).document
        val home = document.select("div.article_standard_view > article.item").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("div.item_content > h3 > a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterElement = element.selectFirst("div.item_header > a > img")
        val posterUrl = posterElement?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        // Wajib pakai Referer Home saat search agar tidak 403 Forbidden
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        
        // Mengambil poster dari berbagai kemungkinan tempat
        val poster = document.selectFirst(".content_banner img")?.attr("src") 
            ?: document.selectFirst("article.item img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val description = document.select("div.video-info p, div.entry-content p, div.description").text()

        val recommendations = document.select("div.article_standard_view > article.item").mapNotNull {
            toSearchResult(it)
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val html = document.html()

        // --- TEKNIK UTAMA: DECODE BASE64 ---
        // Mencari variabel tersembunyi yang berisi semua link server (HgPlay, MixDrop, dll)
        // Pola: string yang dimulai dengan "aHR0cHM6" (https:)
        val base64Regex = Regex("""["'](aHR0cHM6[^"']+)["']""")
        
        base64Regex.findAll(html).forEach { match ->
            try {
                val encodedData = match.groupValues[1]
                // Decode dari Base64 ke Text biasa
                val decodedData = String(Base64.decode(encodedData, Base64.DEFAULT))
                
                // Server dipisahkan oleh tanda koma tiga kali ",,,"
                val rawUrls = decodedData.split(",,,")
                
                rawUrls.forEach { rawUrl ->
                    val url = rawUrl.trim()
                    // Hanya ambil link video (http), abaikan jika ada script aneh
                    if (url.startsWith("http")) {
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                // Lanjut jika decode gagal
            }
        }

        // --- TEKNIK CADANGAN 1: IFRAME ---
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            // Filter link sampah iklan
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google") && !src.contains("tiktok")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // --- TEKNIK CADANGAN 2: REGEX MANUAL ---
        Regex("""(https?:\\/\\/[\\w\\-\\.]+(?:hgplaycdn|mixdrop|dropload|vidhide|streamwish|d000d)[^"']+)""")
            .findAll(html)
            .forEach { match ->
                val url = match.value.replace("\\/", "/")
                loadExtractor(url, data, subtitleCallback, callback)
            }

        return true
    }
}
