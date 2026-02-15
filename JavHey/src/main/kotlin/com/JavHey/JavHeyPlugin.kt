package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: Plugin() {
    override fun load(context: Context) {
        // Kita gunakan nama class baru agar cache lama tidak mengganggu
        registerMainAPI(JavHeyFix())
    }
}
