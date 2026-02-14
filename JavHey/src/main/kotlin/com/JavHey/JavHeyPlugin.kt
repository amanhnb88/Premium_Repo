package com.JavHey

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class JavHeyPlugin: CloudstreamPlugin() {
    override fun load(context: Context) {
        // Mendaftarkan class utama JavHey yang kode-nya sudah kamu berikan sebelumnya
        registerMainAPI(JavHey())
    }
}
