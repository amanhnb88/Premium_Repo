package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: Plugin() {
    override fun load(context: Context) {
        // Register Main Provider Baru
        registerMainAPI(JavHeyFinal())
        
        // Register Extractor Khusus (Byse)
        // Pastikan file ByseSX.kt ada di folder yang sama
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseVepoin())
    }
}
