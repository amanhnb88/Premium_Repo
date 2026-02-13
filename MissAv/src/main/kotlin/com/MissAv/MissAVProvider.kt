package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAVProvider : MainAPI() {
    // Konfigurasi dasar
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    // Helper untuk memperbaiki URL relatif
    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    // Helper untuk mengubah "02:15:30" menjadi menit (Int)
    private fun parseDurationToMinutes(duration: String?): Int? {
        if (duration.isNullOrEmpty()) return null
        return try {
            val parts = duration.trim().split(":").map { it.toInt() }
            when (parts.size) {
                3 -> parts[0] * 60 + parts[1] // Jam:Menit:Detik -> Ambil Jam & Menit
                2 -> parts[0] // Menit:Detik -> Ambil Menit
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==============================
    // 1. HALAMAN UTAMA (HOME)
    // ==============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val sections = document.select("div.sm:container.mx-auto.mb-5.px-4")

        sections.forEach { section ->
            var title = section.selectFirst("h2.text-nord6")?.text()?.trim()
                ?: section.selectFirst("h2")?.text()?.trim()
            
            // Lewati kategori kosong atau acak
            if (title.isNullOrEmpty() || title.equals("Acak", ignoreCase = true)) return@forEach

            val items = section.select("div.thumbnail.group").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homePageList)
    }

    // Helper: Mengubah HTML menjadi SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        
        // Prioritas ambil data-src (lazy load), kalau tidak ada baru src
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        // PENTING: Di MainAPI.kt, SearchResponse TIDAK punya field duration.
        // Jadi kita tidak boleh set duration di sini. Cukup Judul, URL, Poster.
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ==============================
    // 2. PENCARIAN (SEARCH)
    // ==============================
    override suspend fun search(query: String): List<SearchResponse> {
        // Gunakan jalur legacy untuk menghindari proteksi Recombee
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

        // Ambil Tags/Genre
        val tags = document.select("div.text-secondary a[href*='/genres/']").map { it.text() }
        
        // Ambil Aktor & Ubah ke tipe ActorData (Sesuai MainAPI.kt)
        val actors = document.select("div.text-secondary a[href*='/actresses/'], div.text-secondary a[href*='/actors/']")
            .map { element ->
                ActorData(Actor(element.text(), null))
            }

        // Ambil Tahun
        val year = document.selectFirst("time")?.text()?.trim()?.take(4)?.toIntOrNull()

        // Ambil Durasi (Detik ke Menit)
        val durationSeconds = document.selectFirst("meta[property=og:video:duration]")
            ?.attr("content")?.toIntOrNull()
        val durationMinutes = durationSeconds?.div(60)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.actors = actors
            this.year = year
            this.duration = durationMinutes // Di LoadResponse, duration (Int) didukung
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
        val text = app.get(data).text
        
        // Regex untuk mencari ID video dari thumbnail player nineyu.com
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            // Menggunakan builder newExtractorLink sesuai ExtractorApi.kt
            callback.invoke(
                newExtractorLink(
                    source = "MissAV",
                    name = "MissAV (Surrit)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data // Header Referer wajib ada
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }
}
