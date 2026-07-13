package dev.hermes.hermex

import android.app.Application
import dev.hermes.core.di.ServiceLocator

/**
 * Custom Application class. Initializes the ServiceLocator (DI container)
 * once on app start. All repositories are created as singletons here.
 */
class HermexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)
    }
}
