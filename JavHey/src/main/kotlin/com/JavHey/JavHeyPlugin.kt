package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: Plugin() {
    override fun load(context: Context) {
        // Register Provider Utama (Versi V2)
        registerMainAPI(JavHeyV2())
        
        // Register Extractor Tambahan (ByseSX)
        // Pastikan class ByseBuho, dll sudah ada di file ByseSX.kt
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseVepoin())
    }
}
