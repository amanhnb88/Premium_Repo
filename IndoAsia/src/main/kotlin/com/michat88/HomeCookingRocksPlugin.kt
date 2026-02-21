package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HomeCookingRocksPlugin : Plugin() {
    
    // Fungsi ini akan dipanggil pertama kali saat Cloudstream memuat plugin-mu
    override fun load(context: Context) {
        // Mendaftarkan API/Provider yang sudah kamu buat di HomeCookingRocks.kt
        registerMainAPI(HomeCookingRocks())
    }
}
