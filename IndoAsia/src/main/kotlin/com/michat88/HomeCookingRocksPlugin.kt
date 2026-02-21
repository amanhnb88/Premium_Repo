package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HomeCookingRocksPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan Provider Utama (Website HomeCookingRocks)
        registerMainAPI(HomeCookingRocks())
        
        // Mendaftarkan Extractor Khusus untuk Server 2 (4MePlayer)
        registerExtractorAPI(FourMePlayerExtractor())
    }
}
