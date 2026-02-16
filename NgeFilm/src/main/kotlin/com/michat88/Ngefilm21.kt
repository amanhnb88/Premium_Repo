package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class Ngefilm21 : MainAPI() {
    override var mainUrl = "https://new31.ngefilm.site"
    override var name = "Ngefilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private fun Element.getImageAttr(): String? {
        val url = this.attr("data-src").ifEmpty { this.attr("src") }
        return url.replace(Regex("-\\d+x\\d+"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList("Upload Terbaru", home), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".entry-title a") ?: return null
        return newMovieSearchResponse(titleElement.text(), titleElement.attr("href"), TvType.Movie) {
            this.posterUrl = selectFirst(".content-thumbnail img")?.getImageAttr()
            val ratingText = selectFirst(".gmr-rating-item")?.text()?.trim()
            this.score = Score.from10(ratingText?.toDoubleOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val document = app.get(url).document
        return document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        
        var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrEmpty()) {
            poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        }

        val plot = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
        val year = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()?.toIntOrNull()
        val ratingText = document.selectFirst("span[itemprop='ratingValue']")?.text()?.trim()
        val tags = document.select(".gmr-moviedata:contains(Genre) a").map { it.text() }
        val actors = document.select("span[itemprop='actors'] a").mapNotNull {
            val name = it.text()
            if (name.isNotBlank()) ActorData(Actor(name, null)) else null
        }

        var trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")
        if (trailerUrl == null) trailerUrl = document.selectFirst("iframe[src*='youtube.com']")?.attr("src")

        val episodeElements = document.select(".gmr-listseries a").filter {
            it.attr("href").contains("/eps/") && !it.text().contains("Pilih", true)
        }
        val isSeries = episodeElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val response = if (isSeries) {
            val episodes = episodeElements.mapNotNull { element ->
                val epUrl = element.attr("href")
                val epText = element.text()
                val epNum = Regex("(\\d+)").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = element.attr("title").removePrefix("Permalink ke ")
                if (epUrl.isNotEmpty()) newEpisode(epUrl) { this.name = epName; this.episode = epNum } else null
            }
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.score = Score.from10(ratingText?.toDoubleOrNull()); this.tags = tags; this.actors = actors
            }
        }
        if (trailerUrl != null) response.trailers.add(TrailerData(trailerUrl, null, false))
        return response
    }

    // --- BAGIAN PENEMBUSAN TAUTAN (FIXED FOR COMPILATION) ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Ekstraksi via Iframe (Support LiteSpeed data-litespeed-src)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("data-litespeed-src").ifEmpty {
                iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            }
            
            val fixedSrc = fixUrl(src)
            if (fixedSrc.contains("rpmlive.online")) {
                // Bongkar "Kado" Rpmlive via API
                val id = fixedSrc.substringAfterLast("/").substringBefore("?").replace("#", "")
                if (id.length > 3) {
                    val apiRes = app.get("https://playerngefilm21.rpmlive.online/api/v1/video?id=$id", 
                        headers = mapOf(
                            "Referer" to fixedSrc, 
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text
                    
                    val decrypted = decryptRpm(apiRes)
                    if (decrypted.contains("http")) {
                        [span_5](start_span)// FIX: Sesuai signature di ExtractorApi.kt[span_5](end_span)
                        callback.invoke(
                            newExtractorLink(
                                source = "Rpmlive",
                                name = "Server VIP Clean",
                                url = decrypted,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://playerngefilm21.rpmlive.online/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } else if (!fixedSrc.contains("youtube.com") && fixedSrc.isNotBlank()) {
                loadExtractor(fixedSrc, subtitleCallback, callback)
            }
        }

        // 2. Link Download
        document.select(".gmr-download-list a").forEach { link ->
            loadExtractor(link.attr("href"), subtitleCallback, callback)
        }

        return true
    }

    // --- LOGIKA DEKRIPSI MATA TUHAN ---
    private fun decryptRpm(hex: String): String {
        return try {
            [span_6](start_span)// Kunci emas dari DUMP_JS.txt[span_6](end_span)
            val key = "1077efecc0b24d02ace33c1e52e2fb4b"
            val res = StringBuilder()
            val data = hex.chunked(2).map { it.toInt(16) }
            
            for (i in data.indices) {
                val keyChar = key[i % key.length].code
                res.append((data[i] xor keyChar).toChar())
            }
            
            val result = res.toString()
            Regex("""https?://[^\s"']+""").find(result)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
