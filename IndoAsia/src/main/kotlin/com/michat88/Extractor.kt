package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
            // 1. Ekstrak ID dari URL
            val iframeId = Regex("""(?:id=|/v/|/e/)([\w-]+)""").find(url)?.groupValues?.get(1) ?: return
            val host = java.net.URI(url).host
            val refererWeb = referer?.let { java.net.URI(it).host } ?: "homecookingrocks.com"
            val apiUrl = "https://$host/api/v1/video?id=$iframeId&w=360&h=800&r=$refererWeb"

            // 2. Ambil data Hex terenkripsi
            val responseHex = app.get(
                url = apiUrl,
                headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://$host",
                    "Referer" to "https://$host/",
                    "User-Agent" to USER_AGENT
                )
            ).text.trim().replace("\"", "")

            // Validasi: Harus Hexadecimal asli
            if (responseHex.isEmpty() || !responseHex.matches(Regex("^[0-9a-fA-F]+$"))) return 

            // 3. Konversi Hex ke Byte
            val dataBytes = responseHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keySpec = SecretKeySpec("kiemtienmua911ca".toByteArray(), "AES")
            
            var decryptedJson: String? = null

            // 4. Brute Force IV (Kunci kemenangan kita dari Python)
            loop@ for (vLen in 0..30) {
                val q = vLen * (vLen + 2)
                for (vChar in 32..126) {
                    try {
                        val ivBytes = ByteArray(16)
                        var idx = 0
                        for (ke in 1..9) ivBytes[idx++] = (ke + q).toByte()
                        val tt = 111 + vLen
                        val k = tt + 4
                        val Me = vChar * 1 - 2
                        
                        ivBytes[idx++] = q.toByte()
                        ivBytes[idx++] = 111.toByte()
                        ivBytes[idx++] = 0.toByte()
                        ivBytes[idx++] = tt.toByte()
                        ivBytes[idx++] = k.toByte()
                        ivBytes[idx++] = vChar.toByte()
                        ivBytes[idx++] = Me.toByte()

                        val ivSpec = IvParameterSpec(ivBytes)
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                        
                        val result = String(cipher.doFinal())
                        if (result.contains("m3u8")) {
                            decryptedJson = result
                            break@loop
                        }
                    } catch (e: Exception) { continue }
                }
            }

            // 5. Kirim Link ke Player
            decryptedJson?.let { json ->
                val source = Regex(""""source"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
                if (source != null) {
                    callback.invoke(
                        newExtractorLink(name, name, source, INFER_TYPE) {
                            this.referer = url
                        }
                    )
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
