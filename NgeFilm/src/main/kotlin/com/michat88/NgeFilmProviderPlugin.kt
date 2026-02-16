package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NgeFilmProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(NgefilmProvider())
        
        // Register custom extractors
        registerExtractorAPI(NgefilmExtractor())
        registerExtractorAPI(NgefilmGdriveExtractor())
        registerExtractorAPI(NgefilmEmbedExtractor())
    }
}
