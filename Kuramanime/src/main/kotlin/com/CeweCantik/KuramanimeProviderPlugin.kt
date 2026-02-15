package com.CeweCantik

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KuramanimeProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // Daftarin semua alat tempurnya di sini biar dikenalin aplikasi.
        registerMainAPI(KuramanimeProvider())
        registerExtractorAPI(Nyomo())
        registerExtractorAPI(Streamhide())
        registerExtractorAPI(Kuramadrive()) // Ini yang paling penting
        registerExtractorAPI(Lbx())
        registerExtractorAPI(Sunrong())
    }
}
