package com.michat88

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

/**
 * ═══════════════════════════════════════════════════════════
 * NGEFILM21 CLOUDSTREAM PLUGIN
 * ═══════════════════════════════════════════════════════════
 * 
 * Plugin Cloudstream untuk streaming film dari Ngefilm21
 * 
 * @package com.michat88
 * @version 1.0.0
 * @author michat88
 * @site https://new31.ngefilm.site/
 * 
 * Features:
 * - Homepage dengan 12 kategori
 * - Search functionality
 * - Movie, TV Series, Asian Drama support
 * - Multi-server video streaming
 * - Subtitle auto-detect
 * - Metadata lengkap
 * 
 * ═══════════════════════════════════════════════════════════
 */

@CloudstreamPlugin
class NgeFilmProviderPlugin : Plugin() {
    
    /**
     * Plugin initialization
     * Dipanggil saat Cloudstream load plugin
     */
    override fun load(context: Context) {
        // Register main provider
        registerMainAPI(NgefilmProvider())
        
        // Register custom extractors
        registerExtractorAPI(NgefilmExtractor())
        registerExtractorAPI(NgefilmGdriveExtractor())
        registerExtractorAPI(NgefilmEmbedExtractor())
        
        // Log plugin loaded (optional, for debugging)
        println("✅ Ngefilm21 Plugin loaded successfully!")
    }
    
    /**
     * Plugin unload/cleanup
     * Dipanggil saat plugin di-uninstall atau app shutdown
     */
    override fun unload(context: Context) {
        // Cleanup jika perlu (optional)
        println("🔄 Ngefilm21 Plugin unloaded")
    }
}

/**
 * ═══════════════════════════════════════════════════════════
 * USAGE NOTES:
 * ═══════════════════════════════════════════════════════════
 * 
 * File ini adalah plugin manifest untuk Cloudstream.
 * 
 * 1. STRUKTUR PROJECT:
 *    Pastikan file ini berada di: src/main/kotlin/com/michat88/
 *    
 * 2. DEPENDENCIES:
 *    File ini membutuhkan:
 *    - NgefilmProvider.kt
 *    - NgefilmExtractor.kt
 *    
 * 3. BUILD:
 *    Run: ./gradlew make
 *    Output: build/NgefilmPlugin.cs3
 *    
 * 4. INSTALL:
 *    Copy .cs3 file ke:
 *    /Android/data/com.lagradost.cloudstream3/files/plugins/
 *    
 * 5. VERIFY:
 *    - Buka Cloudstream
 *    - Settings → Extensions
 *    - Cek "Ngefilm21" ada di list
 *    
 * ═══════════════════════════════════════════════════════════
 */
