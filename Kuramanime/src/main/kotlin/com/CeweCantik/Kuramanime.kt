package com.CeweCantik

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    [span_0](start_span)override var mainUrl = "https://v14.kuramanime.tel" //[span_0](end_span)
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override var sequentialMainPage = true
    override val hasDownloadSupport = true
    
    var authorization : String? = null 

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    // --- LOGIKA UTAMA: PENJEBOL PLAYER ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val req = app.get(data)
        val res = req.document
        val cookies = req.cookies

        [span_1](start_span)// 1. Ambil data penting dari halaman[span_1](end_span)
        [span_2](start_span)val pid = res.selectFirst("input#postId")?.attr("value") ?: "" //[span_2](end_span)
        [span_3](start_span)val token = res.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return false //[span_3](end_span)
        [span_4](start_span)[span_5](start_span)val dataKk = res.selectFirst("div[data-kk]")?.attr("data-kk") ?: return false //[span_4](end_span)[span_5](end_span)
        
        [span_6](start_span)// 2. Ambil aset rahasia dari file .txt eksternal[span_6](end_span)
        val assets = getAssets(dataKk)

        [span_7](start_span)// 3. Header khusus untuk melewati sistem deteksi (termasuk x-fuck-id)[span_7](end_span)
        val initHeaders = mapOf(
            "X-CSRF-TOKEN" to token,
            "X-Fuck-ID" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}", // rFj8...:ijjA... dari screenshot
            "X-Request-ID" to randomId(),
            "X-Requested-With" to "XMLHttpRequest",
        )

        [span_8](start_span)[span_9](start_span)// 4. Ambil Token Halaman (Route Val) dari file TXT/JS[span_8](end_span)[span_9](end_span)
        val tokenKey = app.get(
            "$mainUrl/${assets.MIX_PREFIX_AUTH_ROUTE_PARAM}${assets.MIX_AUTH_ROUTE_PARAM}",
            headers = initHeaders,
            cookies = cookies
        [span_10](start_span)).text.trim() // Nilai seperti 'CMBy3mfimJ'[span_10](end_span)

        [span_11](start_span)// 5. Gunakan kunci sakti 'Bk3Ko...' yang kita dapatkan dari log curl kamu[span_11](end_span)
        authorization = "Bk3KoZIXvRaszUvQ0SBkRf2xFQZ6AEf6uKuLu"

        [span_12](start_span)// 6. Tembak setiap server yang tersedia[span_12](end_span)
        res.select("select#changeServer option").amap { source ->
            val server = source.attr("value")
            
            [span_13](start_span)// Susun URL dengan parameter acak (Ub3B... dan C2XA...)[span_13](end_span)
            // Format: URL?PAGE_KEY=TOKEN_VAL&SERVER_KEY=SERVER_VAL&page=1
            val playerUrl = "$data?${assets.MIX_PAGE_TOKEN_KEY}=$tokenKey&${assets.MIX_STREAM_SERVER_KEY}=$server&page=1"
            
            val playerHeaders = mapOf(
                "X-CSRF-TOKEN" to token,
                "X-Requested-With" to "XMLHttpRequest",
                "x-fuck-id" to "${assets.MIX_AUTH_KEY}:${assets.MIX_AUTH_TOKEN}"
            )

            [span_14](start_span)// POST ke URL Episode untuk mendapatkan Iframe[span_14](end_span)
            val postRes = app.post(
                playerUrl,
                data = mapOf("authorization" to authorization!!),
                referer = data,
                headers = playerHeaders,
                cookies = cookies
            ).document

            [span_15](start_span)// 7. Ekstraksi Link Video[span_15](end_span)
            if (server.contains("kuramadrive", true)) {
                invokeLocalSource(pid, postRes, subtitleCallback, callback)
            } else {
                postRes.select("iframe").attr("src").let { videoUrl ->
                    if (videoUrl.isNotBlank()) {
                        loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    // --- FUNGSI ASSETS: MEMBONGKAR FILE TXT RAHASIA ---
    private suspend fun getAssets(kk: String): Assets {
        [span_16](start_span)// Ambil nama file .txt dari dalam file .js[span_16](end_span)
        val jsContent = app.get("$mainUrl/assets/js/$kk.js").text
        val txtFile = Regex("""([a-zA-Z0-9]+\.txt)""").find(jsContent)?.groupValues?.get(1) ?: "Ks6sqSgloPTlHMl.txt"

        [span_17](start_span)// Ambil isi file .txt yang berisi window.process.env[span_17](end_span)
        val env = app.get("$mainUrl/assets/$txtFile").text
        
        [span_18](start_span)// Ekstraksi nilai secara dinamis menggunakan Regex[span_18](end_span)
        fun findEnv(key: String) = Regex("""$key:\s*'([^']+)'""").find(env)?.groupValues?.get(1) ?: ""

        return Assets(
            MIX_PREFIX_AUTH_ROUTE_PARAM = "assets/",
            MIX_AUTH_ROUTE_PARAM = txtFile,
            MIX_AUTH_KEY = findEnv("MIX_AUTH_KEY"),       // rFj8fp1nxMuNfKq
            MIX_AUTH_TOKEN = findEnv("MIX_AUTH_TOKEN"),   // ijjAwj6Jze0kscx
            MIX_PAGE_TOKEN_KEY = findEnv("MIX_PAGE_TOKEN_KEY"), // Ub3BzhijicHXZdv
            MIX_STREAM_SERVER_KEY = findEnv("MIX_STREAM_SERVER_KEY") // C2XAPerzX1BM7V9
        )
    }

    private fun randomId(length: Int = 6): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    data class Assets(
        val MIX_PREFIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_ROUTE_PARAM: String,
        val MIX_AUTH_KEY: String,
        val MIX_AUTH_TOKEN: String,
        val MIX_PAGE_TOKEN_KEY: String,
        val MIX_STREAM_SERVER_KEY: String
    )

    // Sisa fungsi (getMainPage, search, load, invokeLocalSource) tetap sama seperti sebelumnya...
}
