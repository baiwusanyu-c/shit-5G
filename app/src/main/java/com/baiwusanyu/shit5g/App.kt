package com.baiwusanyu.shit5g

import android.app.Application
import android.content.Context

class App : Application() {

    companion object {
        @Volatile
        var context: Context? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }
}
