package com.example.qgent

import android.app.Application
import com.example.qgent.data.SessionStore

class QgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        SessionStore.restore()
    }
}
