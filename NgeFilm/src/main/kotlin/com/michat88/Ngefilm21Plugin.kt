package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Ngefilm21Plugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Ngefilm21 ke dalam aplikasi
        registerMainAPI(Ngefilm21())
    }
}
