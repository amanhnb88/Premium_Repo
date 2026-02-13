package com.Mangoporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MangoPorn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "MangoPorn"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = false

    // ==============================
    // 1. MAIN PAGE CONFIGURATION
    // ==============================
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Recent Movies",
        "$mainUrl/trending/" to "Trending",
        "$mainUrl/ratings/" to "Top Rated",
        "$mainUrl/genres/porn-movies/" to "Porn Movies",
        "$mainUrl/xxxclips/" to "XXX Clips"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Pagination logic: https://mangoporn.net/page/2/
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val document = app.get(url).document
        
        val items = document.select("article.item").mapNotNull {
            toSearchResult(it)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h3 > a") ?: return null
        val title = titleElement.text().trim()
        val url = titleElement.attr("href")
        
        // PENTING: Handle Lazy Load WP Fastest Cache (data-wpfc-original-src)
        // Jika atribut wpfc tidak ada, fallback ke src biasa
        val imgElement = element.selectFirst("div.poster img")
        val posterUrl = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        // PERBAIKAN: addDuration dihapus karena tidak didukung di SearchResponse Cloudstream saat ini
        
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. SEARCH
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Fix: Encode query space to + (standard WordPress search)
        // Contoh: "wife husband" -> "wife+husband"
        val fixedQuery = query.replace(" ", "+")
        
        val url = "$mainUrl/?s=$fixedQuery"
        val document = app.get(url).document
        
        return document.select("article.item").mapNotNull {
            toSearchResult(it)
        }
    }

    // ==============================
    // 3. LOAD DETAIL
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val description = document.selectFirst("div.wp-content p")?.text()?.trim()
        
        val imgElement = document.selectFirst("div.poster img")
        val poster = imgElement?.attr("data-wpfc-original-src")?.ifEmpty { 
            imgElement.attr("src") 
        }

        // Mengambil Tags (Genre)
        val tags = document.select(".sgeneros a, .persons a[href*='/genre/']").map { it.text() }
        
        // Mengambil Tahun
        val year = document.selectFirst(".textco a[href*='/year/']")?.text()?.toIntOrNull()
        
        // Mengambil Aktor (Pornstars) yang ada di div #cast
        // PERBAIKAN: Dibungkus dengan ActorData() agar sesuai tipe data
        val actors = document.select("#cast .persons a[href*='/pornstar/']").map { 
            ActorData(Actor(it.text(), null)) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
            this.actors = actors
        }
    }

    // ==============================
    // 4. LOAD LINKS (PLAYER)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // STRATEGI 1: Link Langsung di List Player (#playeroptionsul)
        // Ini metode paling akurat berdasarkan data HTML terakhir
        document.select("#playeroptionsul li a").forEach { link ->
            val href = link.attr("href")
            // Filter link valid
            if (href.startsWith("http")) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }

        // STRATEGI 2: Iframe Fallback (Jika tidak ada list opsi)
        // Kadang player utama ada di dalam iframe #playcontainer
        document.select("#playcontainer iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.startsWith("http")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
