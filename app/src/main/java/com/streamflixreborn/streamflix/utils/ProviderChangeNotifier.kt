package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Utility class to notify ViewModels when the current provider changes
 */
object ProviderChangeNotifier {
    // This is a broadcast event: MainActivity, HomeViewModel, and the home
    // fragment must all observe the same provider/profile refresh.
    private val _providerChangeFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val providerChangeFlow: Flow<Unit> = _providerChangeFlow.asSharedFlow()
    
    /**
     * Notify all listeners that the provider has changed
     */
    fun notifyProviderChanged() {
        _providerChangeFlow.tryEmit(Unit)
    }
}
