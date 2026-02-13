package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id" // Pakai /id agar konten Bahasa Indonesia
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    // Helper untuk memperbaiki URL
    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    // ==============================
    // 1. HALAMAN UTAMA (HOME)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // Mengambil semua kategori secara otomatis
        val sections = document.select("div.sm:container.mx-auto.mb-5.px-4")

        sections.forEach { section ->
            // Coba ambil judul kategori dari berbagai kemungkinan tag
            var title = section.selectFirst("h2.text-nord6")?.text()?.trim()
                ?: section.selectFirst("h2")?.text()?.trim()
            
            // Lewati jika judul kosong atau kategori "Acak" (biasanya kurang relevan)
            if (title.isNullOrEmpty() || title.equals("Acak", ignoreCase = true)) return@forEach

            // Ambil video di dalam kategori ini
            val items = section.select("div.thumbnail.group").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageList)
    }

    // Helper untuk memproses elemen HTML menjadi SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()

        // Ambil gambar (prioritas data-src untuk lazy loading)
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        // Ambil durasi
        val durationText = element.selectFirst("span.absolute.bottom-1.right-1")?.text()?.trim()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(durationText)
        }
    }

    // ==============================
    // 2. PENCARIAN (SEARCH)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Menggunakan jalur "legacy" untuk menghindari token keamanan Recombee
        val url = "$mainUrl/legacy?keyword=$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    // ==============================
    // 3. DETAIL VIDEO (LOAD)
    // ==============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Ambil Judul
        val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown Title"

        // Ambil Deskripsi
        val description = document.selectFirst("div.text-secondary.break-all")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        // Ambil Poster
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        // Ambil Metadata Tambahan
        val tags = document.select("div.text-secondary a[href*='/genres/']").map { it.text() }
        val actors = document.select("div.text-secondary a[href*='/actresses/'], div.text-secondary a[href*='/actors/']").map { it.text() }
        val year = document.selectFirst("time")?.text()?.trim()?.take(4)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
        }
    }

    // ==============================
    // 4. LINK VIDEO (PLAYER)
    // ==============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Ambil source code halaman video
        val text = app.get(data).text

        // LOGIKA PENTING: Mencari UUID dari URL thumbnail nineyu.com
        // Regex ini mencari pola: nineyu.com/UUID/seek
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            // Susun URL Video Surrit
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            callback.invoke(
                ExtractorLink(
                    name = "MissAV (Surrit)",
                    source = "MissAV",
                    url = videoUrl,
                    referer = data, // PENTING: Header Referer harus URL halaman MissAV
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        return false
    }
}
