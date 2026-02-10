package com.example.future_minds // Ensure this matches your package!

import android.app.Application
import com.google.firebase.FirebaseApp

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // This forces Firebase to start the nanosecond the app icon is clicked
        FirebaseApp.initializeApp(this)
    }
}