package com.example.qgent

import android.app.Application
import com.example.qgent.data.SessionStore
import com.example.qgent.di.AppContainer

class QgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
        SessionStore.init(this)
        SessionStore.restore()
    }
}
