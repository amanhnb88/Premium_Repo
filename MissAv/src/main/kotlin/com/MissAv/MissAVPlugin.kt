package com.MissAv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MissAVPlugin: Plugin() {
    override fun load(context: Context) {
        // Pastikan nama class di sini sama persis dengan di file Provider (MissAVProvider)
        registerMainAPI(MissAVProvider())
    }
}
