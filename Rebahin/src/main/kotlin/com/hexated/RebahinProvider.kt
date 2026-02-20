package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.util.Base64

class RebahinProvider : MainAPI() {

    // ================================================================
    // KONFIGURASI DASAR
    // ================================================================
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name   = "Rebahin"
    override val hasMainPage        = true
    override var lang               = "id"
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie, TvType.TvSeries)

    // User-Agent yang sama persis dengan yang digunakan website
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // Data Class untuk parsing JSON API agar lebih aman
    data class ApiPingResponse(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("sources") val sources: List<ApiSource>? = null
    )

    data class ApiSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    // ================================================================
    // HALAMAN UTAMA
    // ================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/trending/"           to "Trending",
        "$mainUrl/movie/"              to "Film Terbaru",
        "$mainUrl/series/"             to "Series Terbaru",
        "$mainUrl/genre/action/"       to "Action",
        "$mainUrl/genre/comedy/"       to "Comedy",
        "$mainUrl/genre/drama/"        to "Drama",
        "$mainUrl/genre/horror/"       to "Horror",
        "$mainUrl/genre/romance/"      to "Romance",
        "$mainUrl/genre/thriller/"     to "Thriller",
        "$mainUrl/genre/korean-drama/" to "Korean Drama",
        "$mainUrl/genre/animation/"    to "Animation",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title  = this.selectFirst("h3 a, h2 a")?.text() ?: return null
        val href   = this.selectFirst("h3 a, h2 a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val type = if (
            this.selectFirst(".gmr-episode-item") != null ||
            href.contains("/series/") || href.contains("/tv/")
        ) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
    }

    // ================================================================
    // PENCARIAN
    // ================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to userAgent))
            .document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    // ================================================================
    // DETAIL FILM / SERIES
    // ================================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title  = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.gmr-movie-data img, div.thumb img")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        val plot   = doc.selectFirst("div.entry-content p, div.gmr-movie-description p")?.text()
        val rating = doc.selectFirst("span.gmr-ratingscore")?.text()
        val genres = doc.select("span.gmr-movie-genre a").map { it.text() }
        val year   = doc.selectFirst("span.gmr-movie-year")?.text()?.trim()?.toIntOrNull()

        val isSeries = doc.selectFirst("div.gmr-episodelist") != null ||
                url.contains("/series/") || url.contains("/tv/")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select("div.gmr-episodelist a, ul.list-episode a, .episodelist a")
                .forEachIndexed { index, ep ->
                    val epUrl = ep.attr("href")
                    if (epUrl.isNotEmpty()) {
                        val epNum = Regex("[?&]ep=(\\d+)").find(epUrl)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                        episodes.add(newEpisode(epUrl) {
                            this.name    = ep.text().ifEmpty { "Episode $epNum" }
                            this.season  = 1
                            this.episode = epNum
                        })
                    }
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating) 
                this.tags = genres
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$url/play/?ep=1&sv=1") {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(rating)
                this.tags = genres
                this.year = year
            }
        }
    }

    // ================================================================
    // EKSTRAK LINK VIDEO
    // ================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // ── STEP 1: Akses halaman /play/ ──────────────────────────────
        val playDoc = app.get(
            data,
            headers = mapOf(
                "User-Agent"      to userAgent,
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer"         to mainUrl,
            )
        ).document

        // ── STEP 2: Temukan iframe /iembed/?source=BASE64 ─────────────
        val iembedSrc = playDoc
            .select("iframe[src*='/iembed/'], iframe[data-src*='/iembed/']")
            .firstOrNull()
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }

        if (iembedSrc != null) {
            val fullUrl = if (iembedSrc.startsWith("http")) iembedSrc else "$mainUrl$iembedSrc"
            processIembed(fullUrl, data, subtitleCallback, callback)
        } else {
            // Fallback: iframe biasa
            playDoc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifEmpty { iframe.attr("data-src") }
                if (src.isNotEmpty()) loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

    // ── STEP 2→3: Decode base64 → akses embed server ─────────────────
    private suspend fun processIembed(
        iembedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceParam = Regex("[?&]source=([A-Za-z0-9+/=]+)").find(iembedUrl)?.groupValues?.get(1)
        val embedUrl = runCatching {
            sourceParam?.let { String(Base64.getDecoder().decode(it)) }
        }.getOrNull()

        if (embedUrl != null) {
            processExternalEmbed(embedUrl, iembedUrl, subtitleCallback, callback)
        } else {
            // Akses iembed langsung, cari iframe di dalamnya
            val doc = app.get(
                iembedUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to referer)
            ).document
            doc.select("iframe[src]").forEach { iframe ->
                loadExtractor(iframe.attr("src"), iembedUrl, subtitleCallback, callback)
            }
        }
    }

    // ── STEP 3→4: Parsing embed server ───────────────────────────────
    private suspend fun processExternalEmbed(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseOrigin = embedUrl.substringBefore("/embed").let {
            if (it.startsWith("http")) it else "https://$it"
        }

        val html = app.get(
            embedUrl,
            headers = mapOf(
                "User-Agent"      to userAgent,
                "Accept"          to "text/html,application/xhtml+xml,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
                "Referer"         to referer,
            )
        ).text

        var found = false
        Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").findAll(html).forEach { m ->
            found = true
            callback.invoke(buildExtractorLink(m.groupValues[1], embedUrl, isM3u8 = true))
        }

        if (!found) {
            Regex(""""file"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""").findAll(html).forEach { m ->
                found = true
                callback.invoke(buildExtractorLink(m.groupValues[1], embedUrl, isM3u8 = false))
            }
        }

        if (!found) {
            val apiPathMatch = Regex("""['"](/api/videos/[^'"]+/ping)['"]""").find(html)
            if (apiPathMatch != null) {
                found = processApiPing(
                    baseOrigin,
                    apiPathMatch.groupValues[1],
                    html,
                    embedUrl,
                    callback
                )
            }
        }

        if (!found) {
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        }
    }

    // ── STEP 4: POST ke /api/videos/.../ping ─────────────────────────
    private suspend fun processApiPing(
        baseOrigin: String,
        apiPath: String,
        pageHtml: String,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val csrfToken = Regex(""""_token"\s*:\s*"([^"]+)"""").find(pageHtml)
                ?.groupValues?.get(1) ?: return false

            val pingId = java.util.UUID.randomUUID().toString().replace("-", "")
            val apiUrl = "$baseOrigin$apiPath"
            
            val requestPayload = mapOf(
                "_token" to csrfToken,
                "__type" to "dawn",
                "pingID" to pingId
            )

            val responseText = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent"   to userAgent,
                    "Accept"       to "*/*",
                    "Content-Type" to "application/json",
                    "Origin"       to baseOrigin,
                    "Referer"      to embedUrl,
                ),
                requestBody = requestPayload.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
            ).text

            var found = false
            val cdnToken = generateCdnToken()

            // Fungsi lokal menyematkan token jika URL dari CDN yg membutuhkannya
            fun appendToken(url: String): String {
                if (cdnToken == null) return url
                return if (url.contains("daisy.groovy.monster") && !url.contains("token=")) {
                    if (url.contains("?")) "$url&token=$cdnToken" else "$url?token=$cdnToken"
                } else url
            }

            // Menggunakan fungsi tryParseJson agar lebih aman
            val apiData = tryParseJson<ApiPingResponse>(responseText)

            apiData?.sources?.forEach { source ->
                source.file?.let { fileUrl ->
                    found = true
                    val finalUrl = appendToken(fileUrl)
                    val isM3u8 = finalUrl.contains(".m3u8") || source.type == "hls"
                    callback.invoke(buildExtractorLink(finalUrl, embedUrl, isM3u8))
                }
            }

            if (!found && apiData?.file != null) {
                found = true
                val finalUrl = appendToken(apiData.file)
                val isM3u8 = finalUrl.contains(".m3u8")
                callback.invoke(buildExtractorLink(finalUrl, embedUrl, isM3u8))
            }

            // Fallback Regex
            if (!found) {
                Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").findAll(responseText).forEach { m ->
                    found = true
                    callback.invoke(buildExtractorLink(appendToken(m.groupValues[1]), embedUrl, isM3u8 = true))
                }
                Regex(""""(https?://[^"]+\.mp4[^"]*)"""").findAll(responseText).forEach { m ->
                    found = true
                    callback.invoke(buildExtractorLink(appendToken(m.groupValues[1]), embedUrl, isM3u8 = false))
                }
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ── HELPER: Menarik IP Publik Pengguna & Membuat Token CDN ───────
    private suspend fun getClientIp(): String? {
        return try {
            app.get("https://api.ipify.org").text.trim()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun generateCdnToken(ua: String = userAgent): String? {
        val clientIp = getClientIp() ?: return null
        val raw = "$clientIp~~$ua"
        return Base64.getEncoder().encodeToString(raw.toByteArray())
    }

    // ── HELPER: Buat ExtractorLink dengan builder terbaru ────────────
    private suspend fun buildExtractorLink(
        url: String,
        referer: String,
        isM3u8: Boolean,
        quality: Int = Qualities.Unknown.value
    ): ExtractorLink {
        return newExtractorLink(
            source  = name,
            name    = if (isM3u8) "$name HLS" else "$name MP4",
            url     = url,
            type    = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = mapOf(
                "User-Agent" to userAgent,
                "Referer"    to referer,
                "Origin"     to referer.substringBefore("/embed").let {
                    if (it.startsWith("http")) it else "https://$it"
                }
            )
        }
    }
}
