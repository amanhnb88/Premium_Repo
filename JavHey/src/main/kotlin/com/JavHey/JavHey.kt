package com.javhey

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

        // --- PERBAIKAN DESKRIPSI ---
        // 1. Coba ambil dari Meta Description (biasanya paling bersih)
        // 2. Coba ambil dari OpenGraph Description
        // 3. Coba ambil dari elemen paragraf di konten
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
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val html = document.html()

        val base64Regex = Regex("""["'](aHR0cHM6[^"']+)["']""")
        
        base64Regex.findAll(html).forEach { match ->
            try {
                val encodedData = match.groupValues[1]
                val decodedData = String(Base64.decode(encodedData, Base64.DEFAULT))
                val rawUrls = decodedData.split(",,,")
                
                rawUrls.forEach { rawUrl ->
                    val url = rawUrl.trim()
                    if (url.startsWith("http")) {
                        loadExtractor(url, data, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
            }
        }

        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google") && !src.contains("tiktok")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        Regex("""(https?:\\/\\/[\\w\\-\\.]+(?:hgplaycdn|mixdrop|dropload|vidhide|streamwish|d000d)[^"']+)""")
            .findAll(html)
            .forEach { match ->
                val url = match.value.replace("\\/", "/")
                loadExtractor(url, data, subtitleCallback, callback)
            }

        return true
    }
}
