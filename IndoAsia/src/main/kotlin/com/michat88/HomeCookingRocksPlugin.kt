package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HomeCookingRocksPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HomeCookingRocks())
        // WAJIB didaftarkan agar class di Extractor.kt dikenali
        registerExtractorAPI(FourMePlayerExtractor())
    }
}
