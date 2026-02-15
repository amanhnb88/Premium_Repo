package com.javhey

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// --- MAIN PROVIDER ---
class JavHeyV4 : MainAPI() { // GANTI JADI V4
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page == 1) request.data.replace("/page=", "") else request.data + page
        val document = app.get(url, headers = headers).document
        val home = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("div.item_content > h3 > a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        val posterUrl = element.selectFirst("div.item_header > a > img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=$query"
        val searchHeaders = headers + mapOf("Referer" to "$mainUrl/")
        val document = app.get(url, headers = searchHeaders).document
        return document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".content_banner img")?.attr("src") 
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.select("div.main_content p").text()
        val recommendations = document.select("div.article_standard_view > article.item").mapNotNull { toSearchResult(it) }

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
    ): Boolean = coroutineScope {
        val html = app.get(data, headers = headers).text
        val rawLinks = mutableSetOf<String>()

        // Regex dari hasil Termux (id="links")
        Regex("""id=["']links["'][^>]*value=["']([^"']+)["']""").findAll(html).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                decoded.split(",,,").forEach { if (it.startsWith("http")) rawLinks.add(it.trim()) }
            } catch (e: Exception) { }
        }
        
        // Regex Fallback
        Regex("""(aHR0cHM6[a-zA-Z0-9+/=]+)""").findAll(html).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                decoded.split(",,,").forEach { if (it.startsWith("http")) rawLinks.add(it.trim()) }
            } catch (e: Exception) { }
        }

        val sortedLinks = rawLinks.sortedBy { if (it.contains("hgplay") || it.contains("mixdrop") || it.contains("byse")) 0 else 1 }

        sortedLinks.forEach { url ->
            launch(Dispatchers.IO) {
                try {
                    // Logic Domain Mapping
                    val fixedUrl = when {
                        url.contains("minochinos.com") -> url.replace("minochinos.com", "streamwish.to")
                        url.contains("terbit2.com") -> url.replace("terbit2.com", "streamwish.to")
                        url.contains("turtle4up.top") -> {
                            val hash = url.substringAfter("#")
                            "https://streamwish.to/e/$hash"
                        }
                        else -> url
                    }
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                } catch (e: Exception) { }
            }
        }
        return@coroutineScope true
    }
}

// --- EXTRACTOR KHUSUS BYSE (Ditanam disini agar mandiri) ---
class ByseBuhoLocal : ByseSXLocal() {
    override var name = "ByseBuho"
    override var mainUrl = "https://bysebuho.com"
}
class BysezejataosLocal : ByseSXLocal() {
    override var name = "Bysezejataos"
    override var mainUrl = "https://bysezejataos.com"
}
class ByseVepoinLocal : ByseSXLocal() {
    override var name = "ByseVepoin"
    override var mainUrl = "https://bysevepoin.com"
}

open class ByseSXLocal : ExtractorApi() {
    override var name = "Byse"
    override var mainUrl = "https://byse.sx"
    override val requiresReferer = true

    private fun b64UrlDecode(s: String): ByteArray {
        val fixed = s.replace('-', '+').replace('_', '/')
        val pad = (4 - fixed.length % 4) % 4
        return base64DecodeArray(fixed + "=".repeat(pad))
    }
    private fun getBaseUrl(url: String) = URI(url).let { "${it.scheme}://${it.host}" }
    private fun getCodeFromUrl(url: String) = URI(url).path?.trimEnd('/')?.substringAfterLast('/') ?: ""

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val refererUrl = getBaseUrl(url)
        val code = getCodeFromUrl(url)
        
        // 1. Get Details
        val detailsUrl = "$refererUrl/api/videos/$code/embed/details"
        val details = app.get(detailsUrl).parsedSafe<DetailsRoot>() ?: return
        
        // 2. Get Playback
        val embedBase = getBaseUrl(details.embedFrameUrl)
        val playbackUrl = "$embedBase/api/videos/${getCodeFromUrl(details.embedFrameUrl)}/embed/playback"
        val headers = mapOf("accept" to "*/*", "referer" to details.embedFrameUrl, "x-embed-parent" to mainUrl)
        val playback = app.get(playbackUrl, headers = headers).parsedSafe<PlaybackRoot>()?.playback ?: return

        // 3. Decrypt
        val keyBytes = b64UrlDecode(playback.keyParts[0]) + b64UrlDecode(playback.keyParts[1])
        val ivBytes = b64UrlDecode(playback.iv)
        val cipherBytes = b64UrlDecode(playback.payload)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
        var jsonStr = String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        if (jsonStr.startsWith("\uFEFF")) jsonStr = jsonStr.substring(1)
        
        val streamUrl = tryParseJson<PlaybackDecrypt>(jsonStr)?.sources?.firstOrNull()?.url ?: return

        M3u8Helper.generateM3u8(name, streamUrl, mainUrl, headers = mapOf("Referer" to refererUrl)).forEach(callback)
    }
}

// Data Classes untuk Byse (Wajib ada)
data class DetailsRoot(@JsonProperty("embed_frame_url") val embedFrameUrl: String)
data class PlaybackRoot(val playback: Playback)
data class Playback(val iv: String, val payload: String, @JsonProperty("key_parts") val keyParts: List<String>)
data class PlaybackDecrypt(val sources: List<PlaybackDecryptSource>)
data class PlaybackDecryptSource(val url: String)
