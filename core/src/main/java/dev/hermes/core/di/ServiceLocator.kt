package dev.hermes.core.di

import android.app.Application
import dev.hermes.core.auth.AuthPrefsRepository
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.data.ConfigRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.WorkspaceRepository

/**
 * ARCH-1 + ARCH-2 fix: Manual dependency injection container.
 *
 * Replaces the old pattern where all 4 repositories extended
 * AndroidViewModel and were created via viewModel() in HermexApp.
 * Now they're plain classes, held as singletons by this object.
 *
 * Benefits:
 * - Repositories are no longer coupled to the Activity lifecycle
 * - Testable — you can construct a ServiceLocator with mock deps
 * - One instance per repository (not one per ViewModel scope)
 * - No DI framework needed (no Hilt/Koin annotation processing)
 *
 * Usage:
 *   ServiceLocator.initialize(application)  // call once from Application.onCreate
 *   ServiceLocator.authRepository          // access from anywhere
 */
object ServiceLocator {

    @Volatile
    private var initialized = false

    lateinit var authRepository: AuthRepository
        private set

    lateinit var sessionRepository: SessionRepository
        private set

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var workspaceRepository: WorkspaceRepository
        private set

    fun initialize(app: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            AuthPrefsRepository.init(app)

            authRepository = AuthRepository(app)
            sessionRepository = SessionRepository(app)
            configRepository = ConfigRepository(app)
            workspaceRepository = WorkspaceRepository(app)

            initialized = true
        }
    }
}
