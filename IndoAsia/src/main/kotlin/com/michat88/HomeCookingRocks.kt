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
                    "User-Agent" to USER_AGENT // Menggunakan default CS3 agar aman dari Cloudflare
                )
            ).text.trim().replace("\"", "")

            // Cek apakah responnya Hexadecimal (Bukan HTML blokir)
            if (responseText.isEmpty() || !responseText.matches(Regex("^[0-9a-fA-F]+$"))) return 

            val dataBytes = responseText.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keySpec = SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
            var decryptedJson: String? = null

            // KEMBALIKAN BRUTE-FORCE GOD MODE (Cuma butuh 1 Milidetik!)
            loop@ for (vLen in 0..30) {
                val q = vLen * (vLen + 2)
                for (vChar in 32..126) {
                    try {
                        val ivBytes = ByteArray(16)
                        var index = 0
                        for (ke in 1..9) ivBytes[index++] = (ke + q).toByte()
                        val tt = 111 + vLen
                        val k = tt + 4
                        val Me = vChar * 1 - 2
                        
                        ivBytes[index++] = q.toByte()
                        ivBytes[index++] = 111.toByte()
                        ivBytes[index++] = 0.toByte()
                        ivBytes[index++] = tt.toByte()
                        ivBytes[index++] = k.toByte()
                        ivBytes[index++] = vChar.toByte()
                        ivBytes[index++] = Me.toByte()

                        val ivSpec = IvParameterSpec(ivBytes)
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                        
                        val resultStr = String(cipher.doFinal())
                        if (resultStr.contains("m3u8") && resultStr.contains("{")) {
                            decryptedJson = resultStr
                            break@loop
                        }
                    } catch (e: Exception) {
                        // Abaikan error tebakan yang salah
                    }
                }
            }

            if (decryptedJson != null) {
                val cleanJson = decryptedJson.substringAfter("{", "").let { if (it.isNotEmpty()) "{$it" else decryptedJson }
                val sourceMatch = Regex(""""source"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                val tiktokMatch = Regex(""""hlsVideoTiktok"\s*:\s*"([^"]+)"""").find(cleanJson)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (sourceMatch != null) {
                    // Menggunakan newExtractorLink resmi agar lolos Build
                    callback.invoke(
                        newExtractorLink(
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
                        newExtractorLink(
                            source = name,
                            name = "Server 2 (TikTok HLS)",
                            url = fullTiktokUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
