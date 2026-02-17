package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Register Main Provider
        registerMainAPI(AdiFilmSemi())
        
        // Register Extractors
        // Mendaftarkan Jeniusplay yang baru (dari IdlixProvider)
        registerExtractorAPI(Jeniusplay())
    }
}
