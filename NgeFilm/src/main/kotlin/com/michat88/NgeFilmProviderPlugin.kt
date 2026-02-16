package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NgefilmPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NgefilmProvider())
    }
}
