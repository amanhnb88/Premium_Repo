package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: Plugin() {
    override fun load(context: Context) {
        // Register Main Provider
        registerMainAPI(JavHeyV2())
        
        // Register Custom Extractors
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseVepoin())
    }
}
