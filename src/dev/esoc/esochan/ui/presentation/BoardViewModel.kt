package dev.esoc.esochan.ui.presentation

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.esoc.esochan.api.AbstractChanModule
import dev.esoc.esochan.api.ChanModule
import dev.esoc.esochan.api.interfaces.CancellableTask
import dev.esoc.esochan.api.models.UrlPageModel
import dev.esoc.esochan.api.util.PageLoaderFromChan
import dev.esoc.esochan.cache.SerializablePage
import dev.esoc.esochan.common.MainApplication
import dev.esoc.esochan.common.SingleLiveEvent
import dev.esoc.esochan.http.interactive.InteractiveException
import dev.esoc.esochan.ui.tabs.TabModel
import dev.esoc.esochan.ui.tabs.TabsTrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

class BoardViewModel(
    private val tabHash: String,
    private val pageType: Int,
    private val chan: ChanModule,
    private val tabModel: TabModel
) : ViewModel() {

    companion object {
        const val TYPE_THREADSLIST = 0
        const val TYPE_POSTSLIST = 1

        /** Attempts to find a just-posted reply while the CDN catches up. */
        private const val POST_REFRESH_ATTEMPTS = 5
        private const val POST_REFRESH_DELAY_MS = 1000L
    }

    private val pagesCache = MainApplication.getInstance().pagesCache
    private val subscriptions = MainApplication.getInstance().subscriptions

    private val _uiState = MutableLiveData<BoardUiState>()
    val uiState: LiveData<BoardUiState> get() = _uiState

    private val _events = SingleLiveEvent<BoardEvent>()
    val events: LiveData<BoardEvent> get() = _events

    private var loadJob: Job? = null

    var isUpdatingNow: Boolean = false
        private set
    var isListLoaded: Boolean = false
        private set

    /**
     * The current SerializablePage being used/updated. Maintained here so that
     * incremental updates (non-fromScratch) reuse the same page object that
     * PageLoaderFromChan mutates in place.
     */
    var currentPage: SerializablePage? = null

    fun loadPage(forceUpdate: Boolean, silent: Boolean) {
        loadPage(forceUpdate, silent, null)
    }

    /**
     * @param expectPostNumber when set (after a successful post), skip If-Modified-Since
     *                         and retry until this post appears or attempts are exhausted.
     */
    fun loadPage(forceUpdate: Boolean, silent: Boolean, expectPostNumber: String?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // Wait for TabsTrackerService to finish updating this tab
            while (TabsTrackerService.getCurrentUpdatingTabId() == tabModel.id) yield()

            val isThreadPage = pageType == TYPE_POSTSLIST

            // Try cache if not yet loaded and (not forcing or is thread page)
            val tryCache = !isListLoaded && (!forceUpdate || isThreadPage)

            if (tryCache) {
                // Try LRU cache first. Network refresh (if forceUpdate) is triggered by the
                // fragment via needUpdateAfter so the list can paint from cache first.
                val cached = pagesCache.getPresentationModel(tabHash)
                if (cached != null) {
                    isListLoaded = true
                    currentPage = cached.source
                    withContext(Dispatchers.Main) {
                        _uiState.value = BoardUiState.Content(
                            page = cached.source,
                            cachedPresentationModel = cached,
                            needUpdateAfter = forceUpdate,
                            putToFileCache = false,
                            itemsCountBefore = 0,
                            expectPostNumber = if (forceUpdate) expectPostNumber else null
                        )
                    }
                    return@launch
                }

                // Try file cache
                val fileCached = pagesCache.getSerializablePage(tabHash)
                if (fileCached != null) {
                    isListLoaded = true
                    currentPage = fileCached
                    withContext(Dispatchers.Main) {
                        _uiState.value = BoardUiState.Content(
                            page = fileCached,
                            cachedPresentationModel = null,
                            needUpdateAfter = forceUpdate,
                            putToFileCache = false,
                            itemsCountBefore = 0,
                            expectPostNumber = if (forceUpdate) expectPostNumber else null
                        )
                    }
                    return@launch
                }
            }

            if (!isListLoaded) {
                withContext(Dispatchers.Main) {
                    _uiState.value = BoardUiState.Loading
                }
            }

            if (!isListLoaded || forceUpdate) {
                loadFromNetwork(silent, expectPostNumber)
            }
        }
    }

    private suspend fun loadFromNetwork(silent: Boolean, expectPostNumber: String?) {
        val fromScratch: Boolean
        val page: SerializablePage

        if (currentPage != null) {
            page = currentPage!!
            fromScratch = false
        } else {
            page = SerializablePage()
            page.pageModel = tabModel.pageModel
            fromScratch = true
            currentPage = page
        }

        val itemsCountBefore = when {
            page.posts != null -> page.posts.size
            page.threads != null -> page.threads.size
            else -> 0
        }

        val maxAttempts = if (expectPostNumber != null && pageType == TYPE_POSTSLIST) {
            POST_REFRESH_ATTEMPTS
        } else {
            1
        }

        isUpdatingNow = true
        var lastResult: LoadResult = LoadResult.Error("Unknown error")

        try {
            for (attempt in 1..maxAttempts) {
                if (expectPostNumber != null) {
                    invalidateThreadCache()
                }

                lastResult = fetchPage(page)

                when (lastResult) {
                    is LoadResult.Success -> {
                        val found = expectPostNumber == null || pageContainsPost(page, expectPostNumber)
                        if (found || attempt == maxAttempts) break
                        delay(POST_REFRESH_DELAY_MS * attempt)
                    }
                    is LoadResult.Error, is LoadResult.Interactive -> break
                }
            }
        } finally {
            isUpdatingNow = false
        }

        when (val result = lastResult) {
            is LoadResult.Success -> {
                subscriptions.checkOwnPost(page, itemsCountBefore)
                val checkSubs = subscriptions.checkSubscriptions(page, itemsCountBefore)
                val newSubPostNumber = if (checkSubs >= 0) page.posts[checkSubs].number else null

                if (fromScratch) {
                    isListLoaded = true
                    withContext(Dispatchers.Main) {
                        _uiState.value = BoardUiState.Content(
                            page = page,
                            cachedPresentationModel = null,
                            needUpdateAfter = false,
                            putToFileCache = true,
                            itemsCountBefore = itemsCountBefore,
                            expectPostNumber = null
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _uiState.value = BoardUiState.Updated(
                            page = page,
                            fromScratch = false,
                            itemsCountBefore = itemsCountBefore,
                            newSubscriptionPostNumber = newSubPostNumber
                        )
                    }
                }
            }

            is LoadResult.Error -> {
                withContext(Dispatchers.Main) {
                    _uiState.value = BoardUiState.Error(result.message, silent)
                }
            }

            is LoadResult.Interactive -> {
                withContext(Dispatchers.Main) {
                    _events.value = BoardEvent.InteractiveRequired(result.exception, silent)
                }
            }
        }
    }

    private fun invalidateThreadCache() {
        val pageModel = tabModel.pageModel ?: return
        if (pageModel.type != UrlPageModel.TYPE_THREADPAGE) return
        if (chan is AbstractChanModule) {
            (chan as AbstractChanModule).invalidateThreadPostsCache(pageModel.boardName, pageModel.threadNumber)
        }
    }

    private fun pageContainsPost(page: SerializablePage, postNumber: String): Boolean {
        val posts = page.posts ?: return false
        for (post in posts) {
            if (postNumber == post.number) return true
        }
        return false
    }

    private suspend fun fetchPage(page: SerializablePage): LoadResult {
        return suspendCancellableCoroutine { cont ->
            val bridgedTask = object : CancellableTask.BaseCancellableTask() {
                override fun isCancelled(): Boolean {
                    return super.isCancelled() || !cont.isActive
                }
            }

            val pageLoader = PageLoaderFromChan(
                page,
                object : PageLoaderFromChan.PageLoaderCallback {
                    override fun onSuccess() {
                        if (cont.isActive) cont.resume(LoadResult.Success)
                    }

                    override fun onError(message: String?) {
                        if (cont.isActive) cont.resume(LoadResult.Error(message ?: "Unknown error"))
                    }

                    override fun onInteractiveException(e: InteractiveException) {
                        if (cont.isActive) cont.resume(LoadResult.Interactive(e))
                    }
                },
                chan,
                bridgedTask
            )

            cont.invokeOnCancellation { bridgedTask.cancel() }
            pageLoader.run()
        }
    }

    fun cancelLoad() {
        loadJob?.cancel()
        isUpdatingNow = false
    }

    private sealed interface LoadResult {
        data object Success : LoadResult
        data class Error(val message: String) : LoadResult
        data class Interactive(val exception: InteractiveException) : LoadResult
    }

    class Factory(
        private val tabHash: String,
        private val pageType: Int,
        private val chan: ChanModule,
        private val tabModel: TabModel
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BoardViewModel(tabHash, pageType, chan, tabModel) as T
        }
    }
}
