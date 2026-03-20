package com.streamvault.app.ui.screens.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.streamvault.app.ui.components.SearchInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.ContinueWatchingRow
import com.streamvault.app.ui.components.SavedCategoryContextCard
import com.streamvault.app.ui.components.SavedCategoryShortcut
import com.streamvault.app.ui.components.SavedCategoryShortcutsRow
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import com.streamvault.app.ui.components.SeriesCard
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.LibraryFilterType
import com.streamvault.domain.model.LibrarySortBy
import com.streamvault.domain.model.Series
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.dialogs.DeleteGroupDialog
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.shell.BrowseHeroPanel
import com.streamvault.app.ui.components.shell.BrowseSearchLaunchCard
import com.streamvault.app.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.shell.VodActionChip
import com.streamvault.app.ui.components.shell.VodActionChipRow
import com.streamvault.app.ui.components.shell.VodCategoryOption
import com.streamvault.app.ui.components.shell.VodCategoryPickerDialog
import com.streamvault.app.ui.components.shell.VodHeroStrip
import com.streamvault.app.ui.components.shell.VodSectionHeader
import com.streamvault.app.ui.screens.vod.HandleVodUserMessage
import com.streamvault.app.ui.screens.vod.ProtectedVodPinDialog
import com.streamvault.app.ui.screens.vod.VodBrowseDefaults

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(
    onSeriesClick: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: SeriesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingSeriesId by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    HandleVodUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::userMessageShown
    )

    BackHandler(enabled = uiState.selectedCategory != null && !uiState.isReorderMode) {
        viewModel.selectCategory(null)
    }

    ProtectedVodPinDialog(
        visible = showPinDialog,
        error = pinError,
        incorrectPinMessage = context.getString(R.string.series_incorrect_pin),
        onDismissRequest = {
            showPinDialog = false
            pinError = null
            pendingSeriesId = null
        },
        onVerified = {
            showPinDialog = false
            pinError = null
            pendingSeriesId?.let(onSeriesClick)
            pendingSeriesId = null
        },
        onErrorChange = { pinError = it },
        verifyPin = viewModel::verifyPin
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_series),
            subtitle = null,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
        if (uiState.isReorderMode && uiState.reorderCategory != null) {
            ReorderTopBar(
                categoryName = uiState.reorderCategory!!.name,
                onSave = { viewModel.saveReorder() },
                onCancel = { viewModel.exitCategoryReorderMode() },
                subtitle = stringResource(R.string.series_reorder_subtitle)
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.series_loading),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (uiState.errorMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.home_error_load_failed),
                    subtitle = uiState.errorMessage ?: ""
                )
            }
        } else if (uiState.seriesByCategory.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.series_no_found),
                    subtitle = stringResource(R.string.series_no_found_subtitle)
                )
            }
        } else {
            SeriesVodContent(
                uiState = uiState,
                selectedFilterType = uiState.selectedLibraryFilterType,
                onSelectedFilterTypeChange = viewModel::setSelectedLibraryFilterType,
                selectedSortBy = uiState.selectedLibrarySortBy,
                onSelectedSortByChange = viewModel::setSelectedLibrarySortBy,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onSeriesClick = onSeriesClick,
                onProtectedSeriesClick = { seriesId ->
                    pendingSeriesId = seriesId
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onOpenContinueWatching = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.IN_PROGRESS)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.LIBRARY)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenTopRated = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.TOP_RATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.RATING)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenFresh = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.RECENTLY_UPDATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.UPDATED)
                    viewModel.selectFullLibraryBrowse()
                },
                onLoadMore = viewModel::loadMoreSelectedCategory,
                onDismissReorder = viewModel::exitCategoryReorderMode
            )
        }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (uiState.showDialog && uiState.selectedSeriesForDialog != null) {
        val series = uiState.selectedSeriesForDialog!!
        com.streamvault.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = series.name,
            groups = uiState.categories.filter { it.isVirtual && it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID },
            isFavorite = series.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (series.isFavorite) viewModel.removeFavorite(series) else viewModel.addFavorite(series)
            },
            onAddToGroup = { group -> viewModel.addToGroup(series, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(series, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) }
        )
    }

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        DeleteGroupDialog(
            groupName = uiState.groupToDelete!!.name,
            onDismissRequest = { viewModel.cancelDeleteGroup() },
            onConfirmDelete = { viewModel.confirmDeleteGroup() }
        )
    }

    if (uiState.selectedCategoryForOptions != null) {
        val category = uiState.selectedCategoryForOptions!!
        com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onRename = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onDelete = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
                { viewModel.requestDeleteGroup(category) }
            } else null,
            onReorderChannels = if (category.isVirtual) {
                { viewModel.enterCategoryReorderMode(category) }
            } else null
        )
    }

    if (uiState.showRenameGroupDialog && uiState.groupToRename != null) {
        RenameGroupDialog(
            initialName = uiState.groupToRename!!.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }
}

@Composable
private fun SeriesVodContent(
    uiState: SeriesUiState,
    selectedFilterType: LibraryFilterType,
    onSelectedFilterTypeChange: (LibraryFilterType) -> Unit,
    selectedSortBy: LibrarySortBy,
    onSelectedSortByChange: (LibrarySortBy) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onProtectedSeriesClick: (Long) -> Unit,
    onShowDialog: (Series) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenTopRated: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit
) {
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteSeries = uiState.seriesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshSeries = uiState.libraryLensRows[SeriesLibraryLens.FRESH].orEmpty()
    val topRatedSeries = uiState.libraryLensRows[SeriesLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroSeries = freshSeries.firstOrNull() ?: topRatedSeries.firstOrNull() ?: favoriteSeries.firstOrNull()
    val categoryOptions = remember(uiState.categoryNames, uiState.categoryCounts) {
        uiState.categoryNames.map { name ->
            VodCategoryOption(
                name = name,
                count = uiState.categoryCounts[name] ?: 0,
                onClick = { onSelectCategory(name) }
            )
        }
    }

    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = categoryOptions,
            onDismiss = { showCategoryPicker = false }
        )
    }

    if (uiState.selectedCategory == null) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            if (heroSeries != null) {
                item("hero") {
                    VodHeroStrip(
                        title = heroSeries.name,
                        subtitle = heroSeries.plot?.takeIf { it.isNotBlank() }
                            ?: heroSeries.genre
                            ?: stringResource(R.string.series_library_lens_subtitle),
                        actionLabel = stringResource(R.string.player_resume).substringBefore(" "),
                        onClick = {
                            val isLocked = (heroSeries.isAdult || heroSeries.isUserProtected) && uiState.parentalControlLevel == 1
                            if (isLocked) onProtectedSeriesClick(heroSeries.id) else onSeriesClick(heroSeries.id)
                        },
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                    )
                }
            }

            item("actions") {
                VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "browse_all",
                                label = stringResource(R.string.library_full_browse_title_series),
                                detail = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                onClick = onSelectFullLibraryBrowse
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.series_categories_title),
                                detail = "${uiState.categoryNames.size} groups",
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        if (favoriteSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "favorites",
                                    label = stringResource(R.string.favorites_title),
                                    detail = stringResource(R.string.library_saved_items_count, favoriteSeries.size),
                                    onClick = { onSelectCategory(uiState.favoriteCategoryName) }
                                )
                            )
                        }
                        if (continueWatching.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "resume",
                                    label = stringResource(R.string.library_lens_continue),
                                    detail = "${continueWatching.size} items",
                                    onClick = onOpenContinueWatching
                                )
                            )
                        }
                        if (topRatedSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedSeries.size} picks",
                                    onClick = onOpenTopRated
                                )
                            )
                        }
                        if (freshSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_series),
                                    detail = "${freshSeries.size} picks",
                                    onClick = onOpenFresh
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            if (continueWatching.isNotEmpty()) {
                item("continue") {
                    ContinueWatchingRow(
                        items = continueWatching,
                        onItemClick = { history -> onSeriesClick(history.seriesId ?: history.contentId) }
                    )
                }
            }

            if (favoriteSeries.isNotEmpty()) {
                item("favorites_header") {
                    VodSectionHeader(
                        title = stringResource(R.string.favorites_title),
                        onSeeAll = { onSelectCategory(uiState.favoriteCategoryName) }
                    )
                }
                item("favorites_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(favoriteSeries, key = { it.id }) { series ->
                            val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                            SeriesCard(
                                series = series,
                                isLocked = isLocked,
                                onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                                onLongClick = { onShowDialog(series) },
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }

            if (freshSeries.isNotEmpty()) {
                item("fresh_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_fresh_series))
                }
                item("fresh_row") {
                    CategoryRow(
                        title = "",
                        items = freshSeries,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) }
                        )
                    }
                }
            }

            if (topRatedSeries.isNotEmpty()) {
                item("top_header") {
                    VodSectionHeader(title = stringResource(R.string.library_lens_top_rated))
                }
                item("top_row") {
                    CategoryRow(
                        title = "",
                        items = topRatedSeries,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) }
                        )
                    }
                }
            }

            items(
                items = uiState.seriesByCategory.entries.filter { (name, items) ->
                    name != uiState.favoriteCategoryName && items.isNotEmpty()
                }.take(8),
                key = { it.key }
            ) { (categoryName, seriesList) ->
                VodSectionHeader(
                    title = categoryName,
                    onSeeAll = { onSelectCategory(categoryName) }
                )
                CategoryRow(
                    title = "",
                    items = seriesList,
                    onSeeAll = null,
                    keySelector = { it.id }
                ) { series ->
                    val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                    SeriesCard(
                        series = series,
                        isLocked = isLocked,
                        onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                        onLongClick = { onShowDialog(series) }
                    )
                }
            }
        }
        return
    }

    val baseSeries = uiState.selectedCategoryItems
    val filteredGridSeries = remember(baseSeries, uiState.isReorderMode, uiState.filteredSeries) {
        if (uiState.isReorderMode) uiState.filteredSeries else baseSeries
    }
    var draggingSeries by remember { mutableStateOf<Series?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        VodActionChipRow(
            actions = buildList {
                add(
                    VodActionChip(
                        key = "back_home",
                        label = stringResource(R.string.nav_series),
                        detail = stringResource(R.string.category_see_all),
                        onClick = { onSelectCategory(null) }
                    )
                )
                add(
                    VodActionChip(
                        key = uiState.fullLibraryCategoryName,
                        label = stringResource(R.string.library_full_browse_title_series),
                        detail = "${uiState.libraryCount} titles",
                        onClick = onSelectFullLibraryBrowse
                    )
                )
                add(
                    VodActionChip(
                        key = "categories",
                        label = stringResource(R.string.series_categories_title),
                        detail = "${uiState.categoryNames.size} groups",
                        onClick = { showCategoryPicker = true }
                    )
                )
                if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                    add(
                        VodActionChip(
                            key = uiState.selectedCategory ?: "",
                            label = uiState.selectedCategory ?: stringResource(R.string.nav_series),
                            detail = "${uiState.selectedCategoryTotalCount} titles",
                            onClick = { showCategoryPicker = true }
                        )
                    )
                }
            },
            selectedKey = uiState.selectedCategory,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (!uiState.isReorderMode) {
            SearchInput(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(R.string.series_search_placeholder),
                onSearch = {},
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            SelectionChipRow(
                title = stringResource(R.string.library_filter_title),
                chips = seriesFilterChips(),
                selectedKey = selectedFilterType.name,
                onChipSelected = { key ->
                    LibraryFilterType.entries.firstOrNull { it.name == key }?.let(onSelectedFilterTypeChange)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
            SelectionChipRow(
                title = stringResource(R.string.library_sort_title),
                chips = LibrarySortBy.entries.map { sort ->
                    SelectionChip(
                        key = sort.name,
                        label = when (sort) {
                            LibrarySortBy.LIBRARY -> stringResource(R.string.library_sort_library)
                            LibrarySortBy.TITLE -> stringResource(R.string.library_sort_az)
                            LibrarySortBy.RELEASE -> stringResource(R.string.library_sort_release)
                            LibrarySortBy.UPDATED -> stringResource(R.string.library_sort_updated)
                            LibrarySortBy.RATING -> stringResource(R.string.library_sort_rating)
                            LibrarySortBy.WATCH_COUNT -> "Recent Activity"
                        }
                    )
                },
                selectedKey = selectedSortBy.name,
                onChipSelected = { key ->
                    LibrarySortBy.entries.firstOrNull { it.name == key }?.let(onSelectedSortByChange)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                        if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                            draggingSeries = null
                            onDismissReorder()
                            true
                        } else false
                    } else false
                },
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                VodSectionHeader(
                    title = when (uiState.selectedCategory) {
                        uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_series)
                        else -> uiState.selectedCategory ?: stringResource(R.string.nav_series)
                    }
                )
            }

            if (uiState.isLoadingSelectedCategory) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Text(
                                text = stringResource(R.string.series_loading),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                gridItems(filteredGridSeries, key = { it.id }) { series ->
                    val isLocked = (series.isAdult || series.isUserProtected) && uiState.parentalControlLevel == 1
                    val isDraggingThis = draggingSeries == series
                    SeriesCard(
                        series = series,
                        isLocked = isLocked,
                        isReorderMode = uiState.isReorderMode,
                        isDragging = isDraggingThis,
                        onClick = {
                            if (uiState.isReorderMode) {
                                draggingSeries = if (isDraggingThis) null else series
                            } else if (isLocked) {
                                onProtectedSeriesClick(series.id)
                            } else {
                                onSeriesClick(series.id)
                            }
                        },
                        onLongClick = {
                            if (!uiState.isReorderMode) onShowDialog(series)
                        }
                    )
                }

                if (!uiState.isReorderMode && uiState.canLoadMoreSelectedCategory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadMoreCard(
                            label = stringResource(
                                R.string.library_load_more,
                                uiState.selectedCategoryLoadedCount,
                                uiState.selectedCategoryTotalCount
                            ),
                            onClick = onLoadMore,
                            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun seriesLibraryLensLabel(lens: SeriesLibraryLens): String =
    when (lens) {
        SeriesLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        SeriesLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        SeriesLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        SeriesLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_series)
    }

private fun seriesFilterChips(): List<SelectionChip> {
    return listOf(
        SelectionChip(LibraryFilterType.ALL.name, "All"),
        SelectionChip(LibraryFilterType.FAVORITES.name, "Favorites"),
        SelectionChip(LibraryFilterType.IN_PROGRESS.name, "Resume"),
        SelectionChip(LibraryFilterType.UNWATCHED.name, "Unwatched"),
        SelectionChip(LibraryFilterType.RECENTLY_UPDATED.name, "Updated"),
        SelectionChip(LibraryFilterType.TOP_RATED.name, "Top Rated")
    )
}

