package com.truckbro.app

import android.app.Application
import com.truckbro.app.road.Http
import org.osmdroid.config.Configuration

class TruckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Карта OpenStreetMap вимагає, щоб програма себе називала; тайли кешуються на телефоні.
        Configuration.getInstance().apply {
            userAgentValue = Http.UA
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
    }
}
