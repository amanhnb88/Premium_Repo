package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NgeFilmProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // FIX: Menggunakan NgeFilmProvider (Huruf F Besar) sesuai nama class di file sebelah
        registerMainAPI(NgeFilmProvider())
    }
}
