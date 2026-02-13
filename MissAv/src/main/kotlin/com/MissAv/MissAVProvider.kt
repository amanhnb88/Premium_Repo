package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MissAvProvider : MainAPI() {
    override var mainUrl = "https://missav.ws"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    // Fungsi helper untuk memperbaiki URL jika relatif
    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "$mainUrl$this"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        // 1. Kita cari semua container yang membungkus section (seperti "Keluaran terbaru", dll)
        // Selector CSS: div dengan class 'sm:container' dan 'mb-5'
        val sections = document.select("div.sm:container.mx-auto.mb-5.px-4")

        sections.forEach { section ->
            // 2. Ambil Judul Section (Header)
            // Contoh: "Keluaran terbaru", "Tanpa sensor"
            val titleElement = section.selectFirst("h2")
            val title = titleElement?.text()?.trim() ?: "Featured"

            // Lewati section jika tidak ada judul atau isinya kosong (misal section 'Acak')
            if (title.isEmpty() || title.equals("Acak", ignoreCase = true)) return@forEach

            // 3. Ambil daftar video di dalam section tersebut
            // Selector CSS: div dengan class 'thumbnail group'
            val items = section.select("div.thumbnail.group").mapNotNull { element ->
                toSearchResult(element)
            }

            // Jika ada video di section ini, tambahkan ke daftar Home
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageList)
    }

    // Fungsi terpisah untuk memproses satu elemen HTML menjadi data SearchResponse
    private fun toSearchResult(element: Element): SearchResponse? {
        // Cari elemen <a> utama untuk link
        // Kita cari <a> yang punya class text-secondary karena di situ judul lengkapnya
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()

        // Ambil Gambar Poster
        // PENTING: Situs ini pakai 'data-src', bukan 'src'
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }

        // Ambil Durasi Video
        val durationText = element.selectFirst("span.absolute.bottom-1.right-1")?.text()?.trim()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            // addDuration adalah fungsi bawaan Cloudstream untuk mengubah string "2:16:08" jadi milidetik
            addDuration(durationText) 
        }
    }

    // Placeholder untuk Search (akan kita kerjakan nanti)
    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList() 
    }

    // Placeholder untuk Load (akan kita kerjakan nanti)
    override suspend fun load(url: String): LoadResponse? {
        return null
    }

    // Placeholder untuk LoadLinks (akan kita kerjakan nanti)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
