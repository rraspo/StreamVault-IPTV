package com.streamvault.app.ui.screens.vod

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Favorite

data class VodCatalogSnapshot<Item>(
    val grouped: Map<String, List<Item>>,
    val categoryNames: List<String>,
    val categoryCounts: Map<String, Int>,
    val libraryCount: Int
)

suspend fun <Item> buildVodPreviewCatalog(
    providerId: Long,
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    providerCategories: List<Category>,
    providerCategoryCounts: Map<Long, Int>,
    libraryCount: Int,
    loadItemsByIds: suspend (List<Long>) -> List<Item>,
    loadCategoryPreviewRows: suspend (Long, Int) -> Map<Long?, List<Item>>,
    itemId: (Item) -> Long,
    copyWithFavorite: (Item, Boolean) -> Item
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val previewRows = linkedMapOf<String, List<Item>>()
    val countMap = linkedMapOf<String, Int>()

    val favoritesIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .sortedBy(Favorite::position)
        .map(Favorite::contentId)
        .toList()
    if (favoritesIds.isNotEmpty()) {
        val preview = loadItemsByIds(favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT))
            .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
        if (preview.isNotEmpty()) {
            previewRows[VodBrowseDefaults.FAVORITES_CATEGORY] = preview
            countMap[VodBrowseDefaults.FAVORITES_CATEGORY] = favoritesIds.size
        }
    }

    val customCategoryPreviewIds = customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .associateWith { category ->
            allFavorites
                .asSequence()
                .filter { it.groupId == -category.id }
                .sortedBy(Favorite::position)
                .map(Favorite::contentId)
                .take(VodBrowseDefaults.PREVIEW_ROW_LIMIT)
                .toList()
        }

    val idsToPreload = buildSet {
        addAll(favoritesIds.take(VodBrowseDefaults.PREVIEW_ROW_LIMIT))
        customCategoryPreviewIds.values.forEach(::addAll)
    }
    val preloadedById = if (idsToPreload.isEmpty()) {
        emptyMap()
    } else {
        loadItemsByIds(idsToPreload.toList()).associateBy(itemId)
    }

    customCategoryPreviewIds.forEach { (category, previewIds) ->
        if (previewIds.isNotEmpty()) {
            val preview = previewIds.mapNotNull(preloadedById::get)
                .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
            if (preview.isNotEmpty()) {
                previewRows[category.name] = preview
                countMap[category.name] = allFavorites.count { favorite -> favorite.groupId == -category.id }
            }
        }
    }

    val providerPreviews = loadCategoryPreviewRows(providerId, VodBrowseDefaults.PREVIEW_ROW_LIMIT)

    providerCategories
        .sortedBy { it.name.lowercase() }
        .forEach { category ->
            val preview = providerPreviews[category.id].orEmpty()
                .let { items -> markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite) }
            if (preview.isNotEmpty()) {
                previewRows[category.name] = preview
                countMap[category.name] = providerCategoryCounts[category.id] ?: preview.size
            }
        }

    return VodCatalogSnapshot(
        grouped = previewRows,
        categoryNames = previewRows.keys.toList(),
        categoryCounts = countMap,
        libraryCount = libraryCount
    )
}

fun <Item> buildVodSearchCatalog(
    items: List<Item>,
    allFavorites: List<Favorite>,
    customCategories: List<Category>,
    itemId: (Item) -> Long,
    itemCategoryName: (Item) -> String?,
    copyWithFavorite: (Item, Boolean) -> Item,
    uncategorizedName: String
): VodCatalogSnapshot<Item> {
    val globalFavoriteIds = allFavorites
        .asSequence()
        .filter { it.groupId == null }
        .map(Favorite::contentId)
        .toSet()
    val enrichedItems = markVodFavorites(items, globalFavoriteIds, itemId, copyWithFavorite)
    val grouped = enrichedItems
        .groupBy { itemCategoryName(it) ?: uncategorizedName }
        .toMutableMap()

    grouped[VodBrowseDefaults.FAVORITES_CATEGORY] = enrichedItems.filter { item ->
        itemId(item) in globalFavoriteIds
    }

    customCategories
        .filter { it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }
        .forEach { customCategory ->
            val itemIdsInGroup = allFavorites
                .asSequence()
                .filter { it.groupId == -customCategory.id }
                .map(Favorite::contentId)
                .toSet()
            grouped[customCategory.name] = enrichedItems.filter { itemId(it) in itemIdsInGroup }
        }

    val customNames = customCategories.map(Category::name).toSet()
    val categoryNames = grouped.keys.sortedWith(
        compareBy<String> {
            when (it) {
                VodBrowseDefaults.FAVORITES_CATEGORY -> 0
                in customNames -> 1
                else -> 2
            }
        }.thenBy { it }
    )

    return VodCatalogSnapshot(
        grouped = grouped,
        categoryNames = categoryNames,
        categoryCounts = categoryNames.associateWith { name -> grouped[name]?.size ?: 0 },
        libraryCount = items.size
    )
}

fun <Item> markVodFavorites(
    items: List<Item>,
    globalFavoriteIds: Set<Long>,
    itemId: (Item) -> Long,
    copyWithFavorite: (Item, Boolean) -> Item
): List<Item> = items.map { item ->
    copyWithFavorite(item, itemId(item) in globalFavoriteIds)
}