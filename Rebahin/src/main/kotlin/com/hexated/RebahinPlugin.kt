package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RebahinPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider yang sudah kita buat
        // agar terbaca oleh sistem CloudStream
        registerMainAPI(RebahinProvider())
    }
}
