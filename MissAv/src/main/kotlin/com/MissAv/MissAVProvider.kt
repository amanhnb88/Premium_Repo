package com.MissAv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// PERBAIKAN 1: Nama class diubah jadi 'MissAVProvider' (V besar) agar sesuai dengan Plugin
class MissAVProvider : MainAPI() {
    override var mainUrl = "https://missav.ws/id"
    override var name = "MissAV"
    override val supportedTypes = setOf(TvType.NSFW)
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private fun String.toUrl(): String {
        return if (this.startsWith("http")) this else "https://missav.ws$this"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        val sections = document.select("div.sm:container.mx-auto.mb-5.px-4")

        sections.forEach { section ->
            var title = section.selectFirst("h2.text-nord6")?.text()?.trim()
                ?: section.selectFirst("h2")?.text()?.trim()
            
            if (title.isNullOrEmpty() || title.equals("Acak", ignoreCase = true)) return@forEach

            val items = section.select("div.thumbnail.group").mapNotNull { element ->
                toSearchResult(element)
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        // PERBAIKAN 2: Menggunakan 'newHomePageResponse' alih-alih constructor lama
        return newHomePageResponse(homePageList)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.text-secondary") ?: return null
        val url = linkElement.attr("href").toUrl()
        val title = linkElement.text().trim()
        val imgElement = element.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
        val durationText = element.selectFirst("span.absolute.bottom-1.right-1")?.text()?.trim()

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
            addDuration(durationText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/legacy?keyword=$query"
        val document = app.get(url).document
        
        return document.select("div.thumbnail.group").mapNotNull { element ->
            toSearchResult(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base.text-nord6")?.text()?.trim() 
            ?: document.selectFirst("h1")?.text()?.trim() 
            ?: "Unknown Title"

        val description = document.selectFirst("div.text-secondary.break-all")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(data).text
        val regex = """nineyu\.com\\/([0-9a-fA-F-]+)\\/seek""".toRegex()
        val match = regex.find(text)
        
        if (match != null) {
            val uuid = match.groupValues[1]
            val videoUrl = "https://surrit.com/$uuid/playlist.m3u8"

            // PERBAIKAN 3: Menggunakan 'newExtractorLink' builder (sesuai ExtractorApi.kt)
            // Constructor ExtractorLink() yang lama sudah deprecated dan akan error
            callback.invoke(
                newExtractorLink(
                    source = "MissAV",
                    name = "MissAV (Surrit)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }
}
