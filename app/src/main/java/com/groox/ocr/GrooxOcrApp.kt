package com.groox.ocr

import android.app.Application
import com.groox.ocr.data.ModelManager
import com.groox.ocr.engine.OcrEngine
import com.groox.ocr.engine.OrtSessions

/** Manual service locator — no DI framework to keep APK lean. */
class GrooxOcrApp : Application() {
    lateinit var modelManager: ModelManager
        private set
    lateinit var ortSessions: OrtSessions
        private set
    lateinit var ocrEngine: OcrEngine
        private set

    override fun onCreate() {
        super.onCreate()
        modelManager = ModelManager(this)
        modelManager.refresh()
        ortSessions = OrtSessions()
        ocrEngine = OcrEngine(this, modelManager, ortSessions)
    }

    override fun onTerminate() {
        try { ortSessions.close() } catch (_: Exception) {}
        super.onTerminate()
    }
}
