package dev.esoc.esochan.ui.presentation

import dev.esoc.esochan.cache.SerializablePage
import dev.esoc.esochan.http.interactive.InteractiveException

sealed interface BoardUiState {
    data object Loading : BoardUiState

    data class Content(
        val page: SerializablePage,
        val cachedPresentationModel: PresentationModel?,
        val needUpdateAfter: Boolean,
        val putToFileCache: Boolean,
        val itemsCountBefore: Int,
        val expectPostNumber: String? = null
    ) : BoardUiState

    data class Updated(
        val page: SerializablePage,
        val fromScratch: Boolean,
        val itemsCountBefore: Int,
        val newSubscriptionPostNumber: String?
    ) : BoardUiState

    data class Error(val message: String, val silent: Boolean) : BoardUiState
}

sealed interface BoardEvent {
    data class InteractiveRequired(
        val exception: InteractiveException,
        val wasSilent: Boolean
    ) : BoardEvent
}
