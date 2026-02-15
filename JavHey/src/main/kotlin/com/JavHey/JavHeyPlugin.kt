package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: Plugin() {
    override fun load(context: Context) {
        // Daftarkan Provider V4
        registerMainAPI(JavHeyV4())
        
        // Daftarkan Extractor Lokal yang ada di file JavHey.kt
        registerExtractorAPI(ByseBuhoLocal())
        registerExtractorAPI(BysezejataosLocal())
        registerExtractorAPI(ByseVepoinLocal())
    }
}
