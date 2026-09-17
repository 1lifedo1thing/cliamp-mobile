package stream.kleeamp.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import stream.kleeamp.mobile.KleeampApp

/**
 * Manual-DI ViewModel wiring. The graph lives on [KleeampApp] as lazy
 * singletons (no Hilt/Koin); screens obtain a navigation-entry-scoped
 * ViewModel through [appViewModel]. Must be called from a composable —
 * inside a navigation destination body the VM is scoped to that back-stack
 * entry, so per-route VMs take a [key] (account id, playlist slug, ...).
 */
class AppViewModelFactory(
    private val create: () -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    noinline create: (KleeampApp) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as KleeampApp
    val factory = remember(app) { AppViewModelFactory({ create(app) }) }
    return viewModel(key = key, factory = factory)
}
