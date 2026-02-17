package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v14.kuramanime.tel"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // --- LOGIKA LOAD LINKS (JANTUNG SKRIP) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers).document
        
        // 1. Ambil data-kk
        val dataKk = doc.selectFirst("div[data-kk]")?.attr("data-kk") ?: return false
        
        // 2. Cari file .txt rahasia via JS
        val jsUrl = "$mainUrl/assets/js/$dataKk.js"
        val jsContent = app.get(jsUrl, headers = headers).text
        val txtFilename = Regex("""([a-zA-Z0-9]+\.txt)""").find(jsContent)?.groupValues?.get(1) ?: return false
        
        // 3. Ambil Kunci dari file TXT
        val txtUrl = "$mainUrl/assets/$txtFilename"
        val txtContent = app.get(txtUrl, headers = headers).text
        
        val pageKey = Regex("""MIX_PAGE_TOKEN_KEY\s*:\s*'([^']+)'""").find(txtContent)?.groupValues?.get(1) ?: ""
        val serverKey = Regex("""MIX_STREAM_SERVER_KEY\s*:\s*'([^']+)'""").find(txtContent)?.groupValues?.get(1) ?: ""
        val routeVal = Regex("""MIX_JS_ROUTE_PARAM_ATTR\s*:\s*'([^']+)'""").find(txtContent)?.groupValues?.get(1) ?: ""
        val authKey = Regex("""MIX_AUTH_KEY\s*:\s*'([^']+)'""").find(txtContent)?.groupValues?.get(1) ?: ""
        val authToken = Regex("""MIX_AUTH_TOKEN\s*:\s*'([^']+)'""").find(txtContent)?.groupValues?.get(1) ?: ""
        
        // 4. Ambil CSRF Token untuk POST
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""

        // 5. Tembak Player (POST Request)
        // Format URL: mainUrl/episode/path?SERVER_KEY=kuramadrive&PAGE_KEY=ROUTE_VAL&page=1
        val playerUrl = "$data?$serverKey=kuramadrive&$pageKey=$routeVal&page=1"
        
        val response = app.post(
            playerUrl,
            headers = headers.toMutableMap().apply {
                put("X-CSRF-TOKEN", csrf)
                put("x-fuck-id", "$authKey:$authToken")
            },
            data = mapOf("authorization" to "Bk3KoZIXvRaszUvQ0SBkRf2xFQZ6AEf6uKuLu") // Fallback token dari log
        ).text

        // 6. Ekstrak Iframe
        val iframeSrc = Regex("""src=\\?"([^"\\]+)""").find(response)?.groupValues?.get(1)?.replace("\\", "")
        
        if (iframeSrc != null) {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        return true
    }

    // --- FUNGSI SEARCH & LOAD (STANDAR) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/anime?search=$query&order_by=oldest", headers = headers).document
        return document.select("div.col-lg-4.col-md-6.col-sm-6").mapNotNull {
            val title = it.selectFirst("h5 a")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("h5 a")?.attr("href") ?: return@mapNotNull null
            val poster = it.selectFirst("div.set-bg")?.attr("data-setbg")
            newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("div.anime__details__title h3")?.text() ?: ""
        val poster = document.selectFirst("div.anime__details__pic")?.attr("data-setbg")
        val plot = document.selectFirst("div.anime__details__text p")?.text()
        
        val episodes = document.select("div#animeEpisodes a").map {
            Episode(it.attr("href"), it.text())
        }
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.episodes = episodes
        }
    }
}
