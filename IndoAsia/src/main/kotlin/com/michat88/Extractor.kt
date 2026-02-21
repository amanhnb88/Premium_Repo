package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FourMePlayerExtractor : ExtractorApi() {
    override val name = "4MePlayer"
    override val mainUrl = "https://ichinime.4meplayer.pro"
    override val requiresReferer = true

    @Suppress("DEPRECATION") // Mantra agar tidak error di sistem build
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val iframeId = Regex("""(?:id=|/v/|/e/)([\w-]+)""").find(url)?.groupValues?.get(1) ?: return
            val host = java.net.URI(url).host
            val refererWeb = referer?.let { java.net.URI(it).host } ?: "homecookingrocks.com"
            val apiUrl = "https://$host/api/v1/video?id=$iframeId&w=360&h=800&r=$refererWeb"

            val responseText = app.get(
                url = apiUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://$host",
                    "Referer" to "https://$host/",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text.trim().replace("\"", "")

            if (responseText.isEmpty() || !responseText.matches(Regex("^[0-9a-fA-F]+$"))) {
                return 
            }

            val dataBytes = responseText.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keySpec = SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
            val ivBytes = byteArrayOf(
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x00,
                0x6f, 0x00, 0x6f, 0x73, 0x20, 0x1e
            )
            val ivSpec = IvParameterSpec(ivBytes)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            
            val decryptedJson = String(cipher.doFinal())
            val cleanJson = decryptedJson.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else decryptedJson }
            
            val sourceMatch = Regex(""""source"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
            val tiktokMatch = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (sourceMatch != null) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Server 2 (4MePlayer)",
                        url = sourceMatch,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
            if (tiktokMatch != null) {
                val fullTiktokUrl = if (tiktokMatch.startsWith("http")) tiktokMatch else "https://$host$tiktokMatch"
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "Server 2 (TikTok HLS)",
                        url = fullTiktokUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
