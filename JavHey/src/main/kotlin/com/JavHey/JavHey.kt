package com.javhey

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

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
        // Menambahkan Referer ke Home agar search tidak diblokir
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        // Ambil judul dari H1
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        
        // Ambil poster (Coba meta tag dulu, baru elemen img)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".content_banner img")?.attr("src") 
            ?: document.selectFirst("article.item img")?.attr("src")

        // Ambil deskripsi dari Meta Tag (lebih akurat)
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.select("div.main_content p").text()
            ?: "No Description"

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
    ): Boolean = coroutineScope {
        
        // 1. Fetch HTML sebagai Raw Text (Lebih ringan & cepat dibanding parsing DOM)
        val html = app.get(data, headers = headers).text
        val rawLinks = mutableSetOf<String>()

        // 2. Extraction Strategy: Decode Base64 (Prioritas Utama - Sapu Jagat)
        // Mencari variabel JS tersembunyi yang berisi list server (links = "aHR0cHM6...")
        val base64Regex = Regex("""["'](aHR0cHM6[^"']+)["']""")
        base64Regex.findAll(html).forEach { match ->
            try {
                val encodedData = match.groupValues[1]
                // Decode Base64
                val decodedData = String(Base64.decode(encodedData, Base64.DEFAULT))
                // Split berdasarkan pola delimiter ",,," (sesuai log: link1,,,link2,,,)
                decodedData.split(",,,").forEach { rawUrl -> 
                    val url = rawUrl.trim()
                    if (url.startsWith("http")) rawLinks.add(url)
                }
            } catch (e: Exception) { }
        }

        // 3. Fallback: Cari Iframe biasa
        val iframeRegex = Regex("""<iframe[^>]+src=["'](https?:[^"']+)["']""")
        iframeRegex.findAll(html).forEach { match ->
             val url = match.groupValues[1]
             if (!url.contains("facebook") && !url.contains("google") && !url.contains("tiktok")) {
                 rawLinks.add(url)
             }
        }

        // 4. Fallback: Regex Langsung untuk Server Umum
        val fallbackRegex = Regex("""(https?:\\/\\/[\\w\\-\\.]+(?:hgplaycdn|mixdrop|dropload|vidhide|streamwish|d000d)[^"']+)""")
        fallbackRegex.findAll(html).forEach { match ->
            rawLinks.add(match.value.replace("\\/", "/").trim())
        }

        // 5. Prioritization Logic (Sorting)
        // Server cepat ditaruh di antrean depan (0), lambat di belakang (2)
        val fastServers = listOf("hgplay", "mixdrop", "fembed")
        val slowServers = listOf("dood", "streamwish", "filemoon", "vidhide")

        val sortedLinks = rawLinks.sortedBy { url ->
            when {
                fastServers.any { url.contains(it) } -> 0 // Fastest
                slowServers.any { url.contains(it) } -> 2 // Slowest
                else -> 1 // Medium
            }
        }

        // 6. Parallel Execution (Reactive & Non-Blocking)
        // Menjalankan extractor secara paralel untuk setiap link
        sortedLinks.forEach { url ->
            launch(Dispatchers.IO) {
                try {
                    // Callback akan dipanggil segera setelah link ditemukan oleh extractor
                    // Ini memberikan efek UX link muncul satu-satu di layar
                    loadExtractor(url, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Error Silent: Biarkan link ini gagal, jangan matikan parent scope
                }
            }
        }

        // Fungsi menunggu semua child coroutines selesai sebelum return
        return@coroutineScope true
    }
}
