package com.streamvault.app.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LibraryFilterBy
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibraryBrowseQuery
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ContinueWatchingScope
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.util.isPlaybackComplete
import com.streamvault.app.ui.screens.vod.createVodGroup
import com.streamvault.app.ui.screens.vod.incrementVodSelectedCategoryLoadLimit
import com.streamvault.app.ui.screens.vod.buildVodPreviewCatalog
import com.streamvault.app.ui.screens.vod.buildVodSearchCatalog
import com.streamvault.app.ui.screens.vod.loadVodDialogSelection
import com.streamvault.app.ui.screens.vod.loadVodReorderItems
import com.streamvault.app.ui.screens.vod.markVodFavorites
import com.streamvault.app.ui.screens.vod.moveVodItemDown
import com.streamvault.app.ui.screens.vod.moveVodItemUp
import com.streamvault.app.ui.screens.vod.selectVodCategory
import com.streamvault.app.ui.screens.vod.saveVodReorder
import com.streamvault.app.ui.screens.vod.setVodLibraryFilterType
import com.streamvault.app.ui.screens.vod.setVodLibrarySortBy
import com.streamvault.app.ui.screens.vod.setVodSearchQuery
import com.streamvault.app.ui.screens.vod.setVodFavorite
import com.streamvault.app.ui.screens.vod.updateVodGroupMembership
import com.streamvault.app.ui.screens.vod.VodBrowseDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MovieLibraryLens {
    FAVORITES,
    CONTINUE,
    TOP_RATED,
    FRESH
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class MoviesViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val movieRepository: MovieRepository,
    private val preferencesRepository: PreferencesRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val favoriteRepository: FavoriteRepository,
    private val getContinueWatching: GetContinueWatching,
    private val getCustomCategories: GetCustomCategories
) : ViewModel() {
    private companion object {
        const val UNCATEGORIZED = "Uncategorized"
    }

    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategoryLoadLimit = MutableStateFlow(VodBrowseDefaults.SELECTED_CATEGORY_PAGE_SIZE)
    private val _selectedLibraryFilterType = MutableStateFlow(LibraryFilterType.ALL)
    private val _selectedLibrarySortBy = MutableStateFlow(LibrarySortBy.LIBRARY)

    init {
        viewModelScope.launch {
            try {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        getCustomCategories(ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        movieRepository.getCategoryItemCounts(provider.id),
                        movieRepository.getLibraryCount(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts, libraryCount ->
                        MovieCatalogDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = providerCategories,
                            providerCategoryCounts = providerCategoryCounts,
                            libraryCount = libraryCount
                        )
                    }.combine(_searchQuery) { dependencies, query ->
                        MovieCatalogParams(
                            providerId = provider.id,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            providerCategoryCounts = dependencies.providerCategoryCounts,
                            libraryCount = dependencies.libraryCount,
                            query = query.trim()
                        )
                    }
                }
                .flatMapLatest { params ->
                    flow {
                        emit(
                            if (params.query.isBlank()) {
                                buildPreviewCatalog(params)
                            } else {
                                val searchResults = movieRepository.searchMovies(params.providerId, params.query).first()
                                buildSearchCatalog(
                                    movies = searchResults,
                                    allFavorites = params.allFavorites,
                                    customCategories = params.customCategories
                                ).copy(libraryCount = searchResults.size)
                            }
                        )
                    }
                }
                .collect { snapshot ->
                    val isReordering = _uiState.value.isReorderMode
                    _uiState.update {
                        it.copy(
                            moviesByCategory = snapshot.grouped,
                            categoryNames = snapshot.categoryNames,
                            categoryCounts = snapshot.categoryCounts,
                            libraryCount = snapshot.libraryCount,
                            filteredMovies = if (isReordering) it.filteredMovies else emptyList(),
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load movies") }
            }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        getCustomCategories(ContentType.MOVIE),
                        movieRepository.getCategories(provider.id),
                        movieRepository.getCategoryItemCounts(provider.id)
                    ) { allFavorites, customCategories, providerCategories, providerCategoryCounts ->
                        MovieCategorySelectionDependencies(
                            allFavorites = allFavorites,
                            customCategories = customCategories,
                            providerCategories = providerCategories,
                            providerCategoryCounts = providerCategoryCounts
                        )
                    }.combine(
                        combine(
                            _uiState.map { it.selectedCategory }.distinctUntilChanged(),
                            _selectedCategoryLoadLimit,
                            _searchQuery,
                            _selectedLibraryFilterType,
                            _selectedLibrarySortBy
                        ) { selectedCategory, loadLimit, query, filterType, sortBy ->
                            SelectedMovieBrowseSelection(
                                selectedCategory = selectedCategory,
                                loadLimit = loadLimit,
                                query = query.trim(),
                                filterType = filterType,
                                sortBy = sortBy
                            )
                        }
                    ) { dependencies, selection ->
                        SelectedMovieCategoryRequest(
                            providerId = provider.id,
                            selectedCategory = selection.selectedCategory,
                            loadLimit = selection.loadLimit,
                            query = selection.query,
                            filterType = selection.filterType,
                            sortBy = selection.sortBy,
                            allFavorites = dependencies.allFavorites,
                            customCategories = dependencies.customCategories,
                            providerCategories = dependencies.providerCategories,
                            providerCategoryCounts = dependencies.providerCategoryCounts
                        )
                    }
                }
                .flatMapLatest { request ->
                    flow {
                        emit(loadSelectedCategoryItems(request))
                    }
                }
                .collect { snapshot ->
                    _uiState.update {
                        it.copy(
                            selectedCategoryItems = snapshot.items,
                            selectedCategoryLoadedCount = snapshot.loadedCount,
                            selectedCategoryTotalCount = snapshot.totalCount,
                            canLoadMoreSelectedCategory = snapshot.canLoadMore,
                            isLoadingSelectedCategory = false
                        )
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .collectLatest { provider ->
                    launch {
                        getContinueWatching(
                            providerId = provider.id,
                            limit = 20,
                            scope = ContinueWatchingScope.MOVIES
                        )
                            .collect { history ->
                                _uiState.update {
                                    it.copy(continueWatching = history)
                                }
                            }
                    }
                }
        }

        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .filterNotNull()
                .flatMapLatest { provider ->
                    combine(
                        favoriteRepository.getFavorites(ContentType.MOVIE),
                        playbackHistoryRepository.getRecentlyWatchedByProvider(provider.id, limit = 24),
                        movieRepository.getTopRatedPreview(provider.id, VodBrowseDefaults.PREVIEW_ROW_LIMIT),
                        movieRepository.getFreshPreview(provider.id, VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                    ) { allFavorites, history, topRated, fresh ->
                        MovieLibraryLensDependencies(
                            providerId = provider.id,
                            allFavorites = allFavorites,
                            history = history,
                            topRated = topRated,
                            fresh = fresh
                        )
                    }
                }
                .collectLatest { dependencies ->
                    val globalFavoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .map { it.contentId }
                        .toSet()
                    val favoriteIds = dependencies.allFavorites
                        .asSequence()
                        .filter { it.groupId == null }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                        .toList()
                    val continueIds = dependencies.history
                        .asSequence()
                        .filter { it.contentType == ContentType.MOVIE }
                        .sortedByDescending { it.lastWatchedAt }
                        .distinctBy { it.contentId }
                        .map { it.contentId }
                        .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                        .toList()

                    val favoritePreview = if (favoriteIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(favoriteIds).first().orderByIds(favoriteIds)
                    }.let { movies ->
                        markVodFavorites(movies, globalFavoriteIds, Movie::id) { movie, isFavorite ->
                            movie.copy(isFavorite = isFavorite)
                        }
                    }
                    val continuePreview = if (continueIds.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(continueIds).first().orderByIds(continueIds)
                    }.let { movies ->
                        markVodFavorites(movies, globalFavoriteIds, Movie::id) { movie, isFavorite ->
                            movie.copy(isFavorite = isFavorite)
                        }
                    }

                    _uiState.update {
                        it.copy(
                            libraryLensRows = mapOf(
                                MovieLibraryLens.FAVORITES to favoritePreview,
                                MovieLibraryLens.CONTINUE to continuePreview,
                                MovieLibraryLens.TOP_RATED to markVodFavorites(dependencies.topRated, globalFavoriteIds, Movie::id) { movie, isFavorite ->
                                    movie.copy(isFavorite = isFavorite)
                                },
                                MovieLibraryLens.FRESH to markVodFavorites(dependencies.fresh, globalFavoriteIds, Movie::id) { movie, isFavorite ->
                                    movie.copy(isFavorite = isFavorite)
                                }
                            ).filterValues { rows -> rows.isNotEmpty() }
                        )
                    }
                }
        }

        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { level ->
                _uiState.update { it.copy(parentalControlLevel = level) }
            }
        }

        viewModelScope.launch {
            getCustomCategories(ContentType.MOVIE).collect { categories ->
                _uiState.update { it.copy(categories = categories) }
            }
        }
    }

    fun selectCategory(categoryName: String?) {
        selectVodCategory(
            categoryName = categoryName,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            selectedLibraryFilterType = _selectedLibraryFilterType,
            selectedLibrarySortBy = _selectedLibrarySortBy,
            uiState = _uiState
        ) { selectedCategory, filterType, sortBy, isLoadingSelectedCategory ->
            copy(
                selectedCategory = selectedCategory,
                selectedLibraryFilterType = filterType,
                selectedLibrarySortBy = sortBy,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    fun selectFullLibraryBrowse() {
        selectCategory(VodBrowseDefaults.FULL_LIBRARY_CATEGORY)
    }

    fun loadMoreSelectedCategory() {
        incrementVodSelectedCategoryLoadLimit(
            canLoadMore = _uiState.value.canLoadMoreSelectedCategory,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit
        )
    }

    fun setSearchQuery(query: String) {
        setVodSearchQuery(query, _searchQuery, _uiState) { updatedQuery ->
            copy(searchQuery = updatedQuery)
        }
    }

    fun setSelectedLibraryFilterType(filterType: LibraryFilterType) {
        setVodLibraryFilterType(
            filterType = filterType,
            selectedLibraryFilterType = _selectedLibraryFilterType,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            uiState = _uiState,
            hasSelectedCategory = { it.selectedCategory != null }
        ) { updatedFilterType, isLoadingSelectedCategory ->
            copy(
                selectedLibraryFilterType = updatedFilterType,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    fun setSelectedLibrarySortBy(sortBy: LibrarySortBy) {
        setVodLibrarySortBy(
            sortBy = sortBy,
            selectedLibrarySortBy = _selectedLibrarySortBy,
            selectedCategoryLoadLimit = _selectedCategoryLoadLimit,
            uiState = _uiState,
            hasSelectedCategory = { it.selectedCategory != null }
        ) { updatedSortBy, isLoadingSelectedCategory ->
            copy(
                selectedLibrarySortBy = updatedSortBy,
                selectedCategoryItems = emptyList(),
                selectedCategoryLoadedCount = 0,
                selectedCategoryTotalCount = 0,
                canLoadMoreSelectedCategory = false,
                isLoadingSelectedCategory = isLoadingSelectedCategory
            )
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        return preferencesRepository.verifyParentalPin(pin)
    }

    fun onShowDialog(movie: Movie) {
        viewModelScope.launch {
            val dialogSelection = loadVodDialogSelection(
                item = movie,
                itemId = movie.id,
                contentType = ContentType.MOVIE,
                favoriteRepository = favoriteRepository,
                copyWithFavorite = { currentMovie, isFavorite ->
                    currentMovie.copy(isFavorite = isFavorite)
                }
            )
            _uiState.update {
                it.copy(
                    showDialog = true,
                    selectedMovieForDialog = dialogSelection.selectedItem,
                    dialogGroupMemberships = dialogSelection.groupMemberships
                )
            }
        }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(showDialog = false, selectedMovieForDialog = null) }
    }

    fun addFavorite(movie: Movie) {
        viewModelScope.launch {
            setVodFavorite(movie.id, ContentType.MOVIE, true, favoriteRepository)
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = true)) }
        }
    }

    fun removeFavorite(movie: Movie) {
        viewModelScope.launch {
            setVodFavorite(movie.id, ContentType.MOVIE, false, favoriteRepository)
            _uiState.update { it.copy(selectedMovieForDialog = movie.copy(isFavorite = false)) }
        }
    }

    fun addToGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            val memberships = updateVodGroupMembership(
                itemId = movie.id,
                groupId = group.id,
                contentType = ContentType.MOVIE,
                shouldBeMember = true,
                favoriteRepository = favoriteRepository
            )
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun removeFromGroup(movie: Movie, group: Category) {
        viewModelScope.launch {
            val memberships = updateVodGroupMembership(
                itemId = movie.id,
                groupId = group.id,
                contentType = ContentType.MOVIE,
                shouldBeMember = false,
                favoriteRepository = favoriteRepository
            )
            _uiState.update { it.copy(dialogGroupMemberships = memberships) }
        }
    }

    fun createCustomGroup(name: String) {
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName)
        if (validationError != null) {
            _uiState.update { it.copy(userMessage = validationError) }
            return
        }

        viewModelScope.launch {
            createVodGroup(normalizedName, ContentType.MOVIE, favoriteRepository)
            _uiState.update { it.copy(userMessage = "Created group $normalizedName") }
        }
    }

    fun showCategoryOptions(categoryName: String) {
        val matchedCategory = _uiState.value.categories.find { it.name == categoryName }
            ?: if (categoryName == VodBrowseDefaults.FAVORITES_CATEGORY) {
                Category(
                    id = VodBrowseDefaults.FAVORITES_SENTINEL_ID,
                    name = VodBrowseDefaults.FAVORITES_CATEGORY,
                    type = ContentType.MOVIE,
                    isVirtual = true
                )
            } else {
                null
            }

        if (matchedCategory != null) {
            _uiState.update { it.copy(selectedCategoryForOptions = matchedCategory) }
        }
    }

    fun dismissCategoryOptions() {
        _uiState.update { it.copy(selectedCategoryForOptions = null) }
    }

    fun requestRenameGroup(category: Category) {
        if (!category.isVirtual || category.id == VodBrowseDefaults.FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showRenameGroupDialog = true,
                groupToRename = category,
                renameGroupError = null
            )
        }
    }

    fun cancelRenameGroup() {
        _uiState.update {
            it.copy(
                showRenameGroupDialog = false,
                groupToRename = null,
                renameGroupError = null
            )
        }
    }

    fun confirmRenameGroup(name: String) {
        val category = _uiState.value.groupToRename ?: return
        val normalizedName = name.trim()
        val validationError = validateGroupName(normalizedName, currentGroupId = category.id)
        if (validationError != null) {
            _uiState.update { it.copy(renameGroupError = validationError) }
            return
        }

        viewModelScope.launch {
            favoriteRepository.renameGroup(-category.id, normalizedName)
            _uiState.update {
                it.copy(
                    showRenameGroupDialog = false,
                    groupToRename = null,
                    renameGroupError = null,
                    userMessage = "Renamed group to $normalizedName"
                )
            }
        }
    }

    fun requestDeleteGroup(category: Category) {
        if (!category.isVirtual || category.id == VodBrowseDefaults.FAVORITES_SENTINEL_ID) return
        _uiState.update {
            it.copy(
                selectedCategoryForOptions = null,
                showDeleteGroupDialog = true,
                groupToDelete = category
            )
        }
    }

    fun cancelDeleteGroup() {
        _uiState.update { it.copy(showDeleteGroupDialog = false, groupToDelete = null) }
    }

    fun confirmDeleteGroup() {
        val category = _uiState.value.groupToDelete ?: return
        viewModelScope.launch {
            favoriteRepository.deleteGroup(-category.id)
            _uiState.update {
                it.copy(
                    showDeleteGroupDialog = false,
                    groupToDelete = null,
                    userMessage = "Deleted group ${category.name}"
                )
            }
        }
    }

    fun userMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun enterCategoryReorderMode(category: Category) {
        dismissCategoryOptions()
        viewModelScope.launch {
            val moviesInView = loadReorderMovies(category)
            _uiState.update {
                it.copy(
                    isReorderMode = true,
                    reorderCategory = category,
                    filteredMovies = moviesInView
                )
            }
        }
    }

    fun exitCategoryReorderMode() {
        _uiState.update {
            it.copy(
                isReorderMode = false,
                reorderCategory = null,
                filteredMovies = emptyList()
            )
        }
    }

    fun moveItemUp(movie: Movie) {
        val reordered = moveVodItemUp(_uiState.value.filteredMovies, movie)
        if (reordered !== _uiState.value.filteredMovies) {
            _uiState.update { it.copy(filteredMovies = reordered) }
        }
    }

    fun moveItemDown(movie: Movie) {
        val reordered = moveVodItemDown(_uiState.value.filteredMovies, movie)
        if (reordered !== _uiState.value.filteredMovies) {
            _uiState.update { it.copy(filteredMovies = reordered) }
        }
    }

    fun saveReorder() {
        val state = _uiState.value
        val category = state.reorderCategory ?: return
        val currentList = state.filteredMovies

        exitCategoryReorderMode()

        viewModelScope.launch {
            try {
                saveVodReorder(
                    category = category,
                    currentItems = currentList,
                    contentType = ContentType.MOVIE,
                    favoriteRepository = favoriteRepository,
                    itemId = Movie::id
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun loadReorderMovies(category: Category): List<Movie> {
        return loadVodReorderItems(
            category = category,
            contentType = ContentType.MOVIE,
            favoriteRepository = favoriteRepository,
            loadByIds = { ids -> movieRepository.getMoviesByIds(ids).first() },
            itemId = Movie::id
        )
    }

    private suspend fun buildPreviewCatalog(
        params: MovieCatalogParams
    ): MovieCatalogSnapshot {
        val snapshot = buildVodPreviewCatalog(
            providerId = params.providerId,
            allFavorites = params.allFavorites,
            customCategories = params.customCategories,
            providerCategories = params.providerCategories,
            providerCategoryCounts = params.providerCategoryCounts,
            libraryCount = params.libraryCount,
            loadItemsByIds = { ids -> movieRepository.getMoviesByIds(ids).first() },
            loadCategoryPreviewRows = { providerId, limit ->
                movieRepository.getCategoryPreviewRows(providerId, limit).first()
            },
            itemId = Movie::id,
            copyWithFavorite = { movie, isFavorite -> movie.copy(isFavorite = isFavorite) }
        )
        return MovieCatalogSnapshot(
            grouped = snapshot.grouped,
            categoryNames = snapshot.categoryNames,
            categoryCounts = snapshot.categoryCounts,
            libraryCount = snapshot.libraryCount
        )
    }

    private fun buildSearchCatalog(
        movies: List<Movie>,
        allFavorites: List<com.streamvault.domain.model.Favorite>,
        customCategories: List<Category>
    ): MovieCatalogSnapshot {
        val snapshot = buildVodSearchCatalog(
            items = movies,
            allFavorites = allFavorites,
            customCategories = customCategories,
            itemId = Movie::id,
            itemCategoryName = Movie::categoryName,
            copyWithFavorite = { movie, isFavorite -> movie.copy(isFavorite = isFavorite) },
            uncategorizedName = UNCATEGORIZED
        )
        return MovieCatalogSnapshot(
            grouped = snapshot.grouped,
            categoryNames = snapshot.categoryNames,
            categoryCounts = snapshot.categoryCounts,
            libraryCount = snapshot.libraryCount
        )
    }

    private suspend fun loadSelectedCategoryItems(
        request: SelectedMovieCategoryRequest
    ): SelectedMovieCategorySnapshot {
        if (request.selectedCategory.isNullOrBlank()) {
            return SelectedMovieCategorySnapshot()
        }

        val globalFavoriteIds = request.allFavorites
            .asSequence()
            .filter { it.groupId == null }
            .map { it.contentId }
            .toSet()

        val (selectedItems, totalCount) = when (request.selectedCategory) {
            VodBrowseDefaults.FULL_LIBRARY_CATEGORY -> {
                val result = movieRepository
                    .browseMovies(
                        LibraryBrowseQuery(
                            providerId = request.providerId,
                            categoryId = null,
                            sortBy = request.sortBy,
                            filterBy = LibraryFilterBy(type = request.filterType),
                            searchQuery = request.query,
                            limit = request.loadLimit,
                            offset = 0
                        )
                    )
                    .first()
                result.items to result.totalCount
            }
            VodBrowseDefaults.FAVORITES_CATEGORY -> {
                val ids = request.allFavorites
                    .asSequence()
                    .filter { it.groupId == null }
                    .sortedBy { it.position }
                    .map { it.contentId }
                    .toList()
                val items = if (ids.isEmpty()) {
                    emptyList()
                } else {
                    movieRepository.getMoviesByIds(ids).first().orderByIds(ids)
                }
                val filteredItems = applyLocalBrowseToMovies(
                    items,
                    request.filterType,
                    request.sortBy,
                    request.query
                )
                filteredItems.take(request.loadLimit) to filteredItems.size
            }
            else -> {
                val customCategory = request.customCategories.firstOrNull { it.name == request.selectedCategory }
                if (customCategory != null) {
                    val ids = request.allFavorites
                        .asSequence()
                        .filter { it.groupId == -customCategory.id }
                        .sortedBy { it.position }
                        .map { it.contentId }
                        .toList()
                    val items = if (ids.isEmpty()) {
                        emptyList()
                    } else {
                        movieRepository.getMoviesByIds(ids).first().orderByIds(ids)
                    }
                    val filteredItems = applyLocalBrowseToMovies(
                        items,
                        request.filterType,
                        request.sortBy,
                        request.query
                    )
                    filteredItems.take(request.loadLimit) to filteredItems.size
                } else {
                    val providerCategory = request.providerCategories.firstOrNull { it.name == request.selectedCategory }
                    if (providerCategory != null) {
                        val result = movieRepository
                            .browseMovies(
                                LibraryBrowseQuery(
                                    providerId = request.providerId,
                                    categoryId = providerCategory.id,
                                    sortBy = request.sortBy,
                                    filterBy = LibraryFilterBy(type = request.filterType),
                                    searchQuery = request.query,
                                    limit = request.loadLimit,
                                    offset = 0
                                )
                            )
                            .first()
                        result.items to result.totalCount
                    } else {
                        emptyList<Movie>() to 0
                    }
                }
            }
        }

        val enrichedItems = markVodFavorites(selectedItems, globalFavoriteIds, Movie::id) { movie, isFavorite ->
            movie.copy(isFavorite = isFavorite)
        }
        return SelectedMovieCategorySnapshot(
            items = enrichedItems,
            loadedCount = enrichedItems.size,
            totalCount = totalCount,
            canLoadMore = totalCount > enrichedItems.size
        )
    }

    private fun validateGroupName(name: String, currentGroupId: Long? = null): String? {
        if (name.isBlank()) return "Enter a group name"
        if (name.equals("favorites", ignoreCase = true)) return "Favorites is reserved"

        val duplicate = _uiState.value.categories.any { category ->
            category.id != currentGroupId && category.name.equals(name, ignoreCase = true)
        }
        return if (duplicate) "A movie group with that name already exists" else null
    }

    private fun List<Movie>.orderByIds(ids: List<Long>): List<Movie> {
        val movieMap = associateBy { it.id }
        return ids.mapNotNull { movieMap[it] }
    }

    private fun applyLocalBrowseToMovies(
        items: List<Movie>,
        filterType: LibraryFilterType,
        sortBy: LibrarySortBy,
        query: String
    ): List<Movie> {
        val normalizedQuery = query.trim().lowercase()
        val searched = if (normalizedQuery.isBlank()) {
            items
        } else {
            items.filter { movie ->
                movie.name.contains(normalizedQuery, ignoreCase = true) ||
                    (movie.plot?.contains(normalizedQuery, ignoreCase = true) == true) ||
                    (movie.genre?.contains(normalizedQuery, ignoreCase = true) == true)
            }
        }
        val filtered = when (filterType) {
            LibraryFilterType.ALL -> searched
            LibraryFilterType.FAVORITES -> searched.filter { it.isFavorite }
            LibraryFilterType.IN_PROGRESS -> searched.filter { movie ->
                movie.watchProgress > 0L && !isPlaybackComplete(
                    movie.watchProgress,
                    movie.durationSeconds.takeIf { it > 0 }?.times(1000L) ?: 0L
                )
            }
            LibraryFilterType.UNWATCHED -> searched.filter { it.watchProgress <= 0L }
            LibraryFilterType.RECENTLY_UPDATED -> searched.sortedByDescending(::movieReleaseScore)
            LibraryFilterType.TOP_RATED -> searched.filter { it.rating > 0f }
        }
        return when (sortBy) {
            LibrarySortBy.LIBRARY -> filtered
            LibrarySortBy.TITLE -> filtered.sortedBy { it.name.lowercase() }
            LibrarySortBy.RELEASE -> filtered.sortedByDescending(::movieReleaseScore)
            LibrarySortBy.UPDATED -> filtered.sortedByDescending(::movieReleaseScore)
            LibrarySortBy.RATING -> filtered.sortedByDescending { it.rating }
            LibrarySortBy.WATCH_COUNT -> filtered.sortedByDescending { it.lastWatchedAt }
        }
    }

    private fun movieReleaseScore(movie: Movie): Long {
        return movie.releaseDate
            ?.filter { it.isDigit() }
            ?.take(8)
            ?.toLongOrNull()
            ?: movie.year?.toLongOrNull()
            ?: 0L
    }
}

private data class MovieCatalogParams(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int,
    val query: String
)

private data class MovieCatalogDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>,
    val libraryCount: Int
)

private data class MovieCatalogSnapshot(
    val grouped: Map<String, List<Movie>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

private data class MovieLibraryLensDependencies(
    val providerId: Long,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val history: List<PlaybackHistory>,
    val topRated: List<Movie>,
    val fresh: List<Movie>
)

private data class MovieCategorySelectionDependencies(
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedMovieCategoryRequest(
    val providerId: Long,
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val filterType: LibraryFilterType,
    val sortBy: LibrarySortBy,
    val allFavorites: List<com.streamvault.domain.model.Favorite>,
    val customCategories: List<Category>,
    val providerCategories: List<Category>,
    val providerCategoryCounts: Map<Long, Int>
)

private data class SelectedMovieBrowseSelection(
    val selectedCategory: String?,
    val loadLimit: Int,
    val query: String,
    val filterType: LibraryFilterType,
    val sortBy: LibrarySortBy
)

private data class SelectedMovieCategorySnapshot(
    val items: List<Movie> = emptyList(),
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    val canLoadMore: Boolean = false
)

data class MoviesUiState(
    val moviesByCategory: Map<String, List<Movie>> = emptyMap(),
    val categoryNames: List<String> = emptyList(),
    val categoryCounts: Map<String, Int> = emptyMap(),
    val libraryCount: Int = 0,
    val favoriteCategoryName: String = "\u2605 Favorites",
    val fullLibraryCategoryName: String = "__full_library__",
    val libraryLensRows: Map<MovieLibraryLens, List<Movie>> = emptyMap(),
    val selectedCategory: String? = null,
    val selectedCategoryItems: List<Movie> = emptyList(),
    val selectedCategoryLoadedCount: Int = 0,
    val selectedCategoryTotalCount: Int = 0,
    val canLoadMoreSelectedCategory: Boolean = false,
    val isLoadingSelectedCategory: Boolean = false,
    val searchQuery: String = "",
    val selectedLibraryFilterType: LibraryFilterType = LibraryFilterType.ALL,
    val selectedLibrarySortBy: LibrarySortBy = LibrarySortBy.LIBRARY,
    val continueWatching: List<PlaybackHistory> = emptyList(),
    val isLoading: Boolean = true,
    val parentalControlLevel: Int = 0,
    val showDialog: Boolean = false,
    val selectedMovieForDialog: Movie? = null,
    val categories: List<Category> = emptyList(),
    val dialogGroupMemberships: List<Long> = emptyList(),
    val userMessage: String? = null,
    val selectedCategoryForOptions: Category? = null,
    val showRenameGroupDialog: Boolean = false,
    val groupToRename: Category? = null,
    val renameGroupError: String? = null,
    val showDeleteGroupDialog: Boolean = false,
    val groupToDelete: Category? = null,
    val isReorderMode: Boolean = false,
    val reorderCategory: Category? = null,
    val filteredMovies: List<Movie> = emptyList(),
    val errorMessage: String? = null
)
