package com.duoopen

import android.app.Application
import com.duoopen.power.PowerState
import com.duoopen.settings.DuoSettings

class DuoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The activity, wallpaper and accessibility services share this process
        // and read the same settings flow.
        DuoSettings.init(this)
        PowerState.init(this)
    }
}
