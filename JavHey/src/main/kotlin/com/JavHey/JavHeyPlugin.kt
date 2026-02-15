package com.javhey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin : CloudstreamPlugin() {
    override fun load(context: Context) {
        // Kita hanya perlu mendaftarkan MainAPI saja.
        // Ekstraktor Byse sudah dipanggil secara manual di dalam JavHey.kt
        registerMainAPI(JavHey())
    }
}
