package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class JavHey : MainAPI() {
    override var mainUrl = "https://javhey.com"
    override var name = "JAVHEY"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Header default yang meniru browser Android (sesuai log kamu)
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/videos/paling-baru/page=" to "Paling Baru",
        "$mainUrl/videos/paling-dilihat/page=" to "Paling Dilihat",
        "$mainUrl/videos/top-rating/page=" to "Top Rating",
        "$mainUrl/videos/jav-sub-indo/page=" to "JAV Sub Indo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) {
            request.data.replace("/page=", "")
        } else {
            request.data + page
        }

        val document = app.get(url, headers = defaultHeaders).document
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
        // Menambahkan Referer khusus untuk search sesuai log
        val searchHeaders = defaultHeaders + mapOf("Referer" to "$mainUrl/")
        
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst(".content_banner img")?.attr("src")
            ?: document.selectFirst("article.item img")?.attr("src")
            ?: document.selectFirst("div.player_content img")?.attr("src")

        val description = document.select("div.video-info p, div.entry-content p").text()

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
        val document = app.get(data, headers = defaultHeaders).document

        // 1. Cek iframe standar
        document.select("iframe").forEach { iframe ->
            var src = iframe.attr("src")
            if (src.startsWith("//")) src = "https:$src"
            if (src.isNotBlank() && !src.contains("facebook") && !src.contains("google")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // 2. Cek Script Embed (Regex)
        val scriptContent = document.select("script").html()
        Regex("""(https?:\\/\\/[\\w\\-\\.]+(?:streamwish|dood|filemoon|vidhide|javorb)[^"']+)""")
            .findAll(scriptContent)
            .forEach { match ->
                val url = match.value.replace("\\/", "/")
                loadExtractor(url, data, subtitleCallback, callback)
            }

        // 3. Eksperimental: Cek AJAX get_link.php (jika metode di atas gagal)
        // Logika ini ditambahkan karena ada log request ke get_link.php
        // Biasanya ini dipanggil saat user klik tombol play atau server
        try {
            val ajaxUrl = "$mainUrl/get_link.php"
            val ajaxHeaders = defaultHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to data,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            // Coba post dengan option=1 sesuai log
            val response = app.post(ajaxUrl, headers = ajaxHeaders, data = mapOf("option" to "1"))
            
            // Analisis response text (mungkin berisi HTML fragmen atau JSON)
            val responseText = response.text
            if (responseText.contains("http")) {
                 // Cari url dalam response
                 Regex("""(https?://[^"'\s]+)""").findAll(responseText).forEach { match ->
                     val extractedUrl = match.value.replace("\\/", "/")
                     if (!extractedUrl.contains("javhey.com")) { // Hindari loop ke diri sendiri
                         loadExtractor(extractedUrl, data, subtitleCallback, callback)
                     }
                 }
            }
        } catch (e: Exception) {
            // Abaikan error AJAX jika tidak krusial
        }

        return true
    }
}
