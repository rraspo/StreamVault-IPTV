package com.streamvault.data.repository

import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.local.entity.ChannelEntity
import com.streamvault.data.mapper.toDomain
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import com.streamvault.data.util.toFtsPrefixQuery
import com.streamvault.data.util.rankSearchResults
import javax.inject.Inject
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import javax.inject.Singleton

@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val preferencesRepository: PreferencesRepository,
    private val parentalControlManager: com.streamvault.domain.manager.ParentalControlManager,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver
) : ChannelRepository {

    private data class ChannelGroupAccumulator(
        var primary: ChannelEntity,
        val alternatives: MutableList<ChannelEntity> = mutableListOf()
    )

    private val channelPriorityComparator = compareBy<ChannelEntity>({ it.errorCount }, { it.name.length })
    private val channelNumberComparator = compareBy<Channel>(
        { it.number <= 0 },
        { it.number.takeIf { number -> number > 0 } ?: Int.MAX_VALUE },
        { it.name.lowercase() }
    )

    override fun getChannels(providerId: Long): Flow<List<Channel>> =
        observeChannels(channelDao.getByProvider(providerId), providerId)

    override fun getChannelsByCategory(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)

    override fun getChannelsByNumber(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlow(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun getChannelsWithoutErrors(providerId: Long, categoryId: Long): Flow<List<Channel>> =
        observeChannels(channelFlowWithoutErrors(providerId, categoryId), providerId)
            .map(::sortChannelsByNumber)

    override fun searchChannelsByCategory(providerId: Long, categoryId: Long, query: String): Flow<List<Channel>> =
        query.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isBlank()) {
                flowOf(emptyList())
            } else {
                combine(
                    if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
                        channelDao.search(providerId, ftsQuery)
                    } else {
                        channelDao.searchByCategory(providerId, categoryId, ftsQuery)
                    },
                    preferencesRepository.parentalControlLevel,
                    parentalControlManager.unlockedCategoriesForProvider(providerId)
                ) { entities, level, unlockedCats ->
                    val filtered = if (level == 2) {
                        entities.filter { entity ->
                            val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                            (!entity.isAdult && !entity.isUserProtected) || isUnlocked
                        }
                    } else {
                        entities
                    }

                    groupAndMapChannels(filtered, unlockedCats)
                }
            }
        }

    override fun getCategories(providerId: Long): Flow<List<Category>> =
        combine(
            categoryDao.getByProviderAndType(providerId, ContentType.LIVE.name),
            channelDao.getByProvider(providerId),
            preferencesRepository.parentalControlLevel,
            parentalControlManager.unlockedCategoriesForProvider(providerId)
        ) { categories: List<CategoryEntity>, channelEntities: List<ChannelEntity>, level: Int, unlockedCats: Set<Long> ->
            val filteredChannels = applyVisibilityFilter(channelEntities, level, unlockedCats)
            val groupedChannels = groupPrimaryChannelEntities(filteredChannels)
            val countMap = groupedChannels
                .mapNotNull { entity -> entity.categoryId }
                .groupingBy { categoryId -> categoryId }
                .eachCount()

            val allChannelsCategory = Category(
                id = ChannelRepository.ALL_CHANNELS_ID,
                name = "All Channels",
                type = ContentType.LIVE,
                count = groupedChannels.size
            )
            
            val mappedCategories = categories.map { entity ->
                val domain = entity.toDomain().copy(count = countMap[entity.categoryId] ?: 0)
                // If unlocked, update domain model (though Category model doesn't strictly need it if we trust the check elsewhere, 
                // but nice for UI to show 'unlocked' icon if we had one. For now just passing through).
                 if (unlockedCats.contains(entity.categoryId)) {
                    domain.copy(isUserProtected = false)
                } else {
                    domain
                }
            }
            
            // Filter categories if level is HIDDEN
            val filteredCategories = if (level == 2) {
                mappedCategories.filter { category ->
                    (!category.isAdult && !category.isUserProtected) || unlockedCats.contains(category.id)
                }
            } else {
                mappedCategories
            }
            
            listOf(allChannelsCategory) + filteredCategories
        }

    override fun searchChannels(providerId: Long, query: String): Flow<List<Channel>> =
        query.toFtsPrefixQuery().let { ftsQuery ->
            if (ftsQuery.isBlank()) {
                flowOf(emptyList())
            } else combine(
                channelDao.search(providerId, ftsQuery),
                preferencesRepository.parentalControlLevel,
                parentalControlManager.unlockedCategoriesForProvider(providerId)
            ) { entities, level, unlockedCats ->
                val filtered = if (level == 2) {
                    entities.filter { entity ->
                        val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                        (!entity.isAdult && !entity.isUserProtected) || isUnlocked
                    }
                } else {
                    entities
                }

                groupAndMapChannels(filtered, unlockedCats)
                    .rankSearchResults(query) { it.name }
            }
        }

    override suspend fun getChannel(channelId: Long): Channel? =
        channelDao.getById(channelId)?.toDomain()

    override suspend fun getStreamInfo(channel: Channel): Result<StreamInfo> = try {
        xtreamStreamUrlResolver.resolveWithMetadata(
            url = channel.streamUrl,
            fallbackProviderId = channel.providerId,
            fallbackStreamId = channel.streamId,
            fallbackContentType = ContentType.LIVE
        )?.let { resolvedStream ->
            Result.success(
                StreamInfo(
                    url = resolvedStream.url,
                    title = channel.name,
                    expirationTime = resolvedStream.expirationTime
                )
            )
        } ?: Result.error("No stream URL available for channel: ${channel.name}")
    } catch (e: Exception) {
        Result.error(e.message ?: "Failed to resolve stream URL for channel: ${channel.name}", e)
    }

    override suspend fun refreshChannels(providerId: Long): Result<Unit> {
        // Refresh is handled by ProviderRepository.refreshProviderData
        return Result.success(Unit)
    }

    override fun getChannelsByIds(ids: List<Long>): Flow<List<Channel>> =
        channelDao.getByIds(ids).map { entities -> entities.map { it.toDomain() } }

    override suspend fun incrementChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.incrementErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to increment channel error count", e)
    }

    override suspend fun resetChannelErrorCount(channelId: Long): Result<Unit> = try {
        channelDao.resetErrorCount(channelId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reset channel error count", e)
    }

    private fun observeChannels(
        source: Flow<List<ChannelEntity>>,
        providerId: Long
    ): Flow<List<Channel>> = combine(
        source,
        preferencesRepository.parentalControlLevel,
        parentalControlManager.unlockedCategoriesForProvider(providerId)
    ) { entities, level, unlockedCats ->
        groupAndMapChannels(applyVisibilityFilter(entities, level, unlockedCats), unlockedCats)
    }

    private fun groupAndMapChannels(entities: List<ChannelEntity>, unlockedCats: Set<Long>): List<Channel> {
        val grouped = LinkedHashMap<String, ChannelGroupAccumulator>()
        entities.forEach { entity ->
            val key = channelGroupKey(entity)
            val existing = grouped[key]
            if (existing == null) {
                grouped[key] = ChannelGroupAccumulator(primary = entity)
            } else if (channelPriorityComparator.compare(entity, existing.primary) < 0) {
                existing.alternatives += existing.primary
                existing.primary = entity
            } else {
                existing.alternatives += entity
            }
        }

        return grouped.values.map { group ->
            val primaryEntity = group.primary
            val alternativeStreams = group.alternatives
                .sortedWith(channelPriorityComparator)
                .map { it.streamUrl }

            val mergedQualityOptions = buildMergedQualityOptions(primaryEntity, alternativeStreams)
            val domain = primaryEntity.toDomain().copy(
                qualityOptions = mergedQualityOptions,
                alternativeStreams = alternativeStreams
            )

            if (primaryEntity.categoryId != null && unlockedCats.contains(primaryEntity.categoryId)) {
                domain.copy(isUserProtected = false, isAdult = false)
            } else {
                domain
            }
        }
    }

    private fun applyVisibilityFilter(
        entities: List<ChannelEntity>,
        level: Int,
        unlockedCats: Set<Long>
    ): List<ChannelEntity> {
        return if (level == 2) {
            entities.filter { entity ->
                val isUnlocked = entity.categoryId != null && unlockedCats.contains(entity.categoryId)
                (!entity.isAdult && !entity.isUserProtected) || isUnlocked
            }
        } else {
            entities
        }
    }

    private fun sortChannelsByNumber(channels: List<Channel>): List<Channel> =
        channels.sortedWith(channelNumberComparator)

    private fun channelFlow(providerId: Long, categoryId: Long): Flow<List<ChannelEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProvider(providerId)
        } else {
            channelDao.getByCategory(providerId, categoryId)
        }

    private fun channelFlowWithoutErrors(providerId: Long, categoryId: Long): Flow<List<ChannelEntity>> =
        if (categoryId == ChannelRepository.ALL_CHANNELS_ID) {
            channelDao.getByProviderWithoutErrors(providerId)
        } else {
            channelDao.getByCategoryWithoutErrors(providerId, categoryId)
        }

    private fun groupPrimaryChannelEntities(entities: List<ChannelEntity>): List<ChannelEntity> {
        val primaries = LinkedHashMap<String, ChannelEntity>()
        entities.forEach { entity ->
            val key = channelGroupKey(entity)
            val current = primaries[key]
            if (current == null || channelPriorityComparator.compare(entity, current) < 0) {
                primaries[key] = entity
            }
        }
        return primaries.values.toList()
    }

    private fun buildMergedQualityOptions(
        primaryEntity: ChannelEntity,
        alternativeStreams: List<String>
    ): List<com.streamvault.domain.model.ChannelQualityOption> {
        val baseOptions = primaryEntity.toDomain().qualityOptions
        val derivedOptions = (listOf(primaryEntity.streamUrl) + alternativeStreams)
            .mapNotNull(::deriveQualityOption)
        return (baseOptions + derivedOptions)
            .distinctBy { option -> option.height to option.label }
            .sortedWith(compareByDescending<com.streamvault.domain.model.ChannelQualityOption> { it.height ?: -1 }.thenBy { it.label })
    }

    private fun deriveQualityOption(url: String): com.streamvault.domain.model.ChannelQualityOption? {
        val match = QUALITY_HEIGHT_REGEX.find(url) ?: return null
        val height = match.groupValues[1].toIntOrNull() ?: return null
        return com.streamvault.domain.model.ChannelQualityOption(
            label = "${height}p",
            height = height,
            url = url
        )
    }

    private companion object {
        val QUALITY_HEIGHT_REGEX = Regex("(?<!\\d)(2160|1440|1080|720|576|480|360|240)p?(?!\\d)", RegexOption.IGNORE_CASE)
    }

    private fun channelGroupKey(entity: ChannelEntity): String =
        if (entity.logicalGroupId.isNotBlank()) entity.logicalGroupId else entity.id.toString()
}
