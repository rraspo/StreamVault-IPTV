# Xtream Index-First Sync Plan

## Table Of Contents

- [Summary](#summary)
- [Phases](#phases)
  - [Phase 0: Plan Document](#phase-0-plan-document)
  - [Phase 1: Data Model And Migration](#phase-1-data-model-and-migration)
  - [Phase 1.5: Compatibility Semantics](#phase-15-compatibility-semantics)
  - [Phase 2: New Xtream Setup Flow](#phase-2-new-xtream-setup-flow)
  - [Phase 3: Browse, Search, And Detail Hydration](#phase-3-browse-search-and-detail-hydration)
  - [Phase 4: Settings Sync Redesign](#phase-4-settings-sync-redesign)
  - [Phase 5: Background Sync And Staleness](#phase-5-background-sync-and-staleness)
  - [Phase 6: Cleanup And Compatibility](#phase-6-cleanup-and-compatibility)
- [Acceptance Criteria](#acceptance-criteria)
- [Public API / Type Changes](#public-api--type-changes)
- [Test Plan](#test-plan)
- [Assumptions And Defaults](#assumptions-and-defaults)

## Summary

Replace the current Xtream full-sync and fast/on-demand category-sync model with an index-first architecture.

Provider setup becomes usable after:

- Xtream authentication succeeds.
- Live TV categories and the full live channel catalog are loaded.
- VOD and series categories are loaded.
- Background jobs are queued for VOD/series summary indexing and EPG.

VOD/series search and browse will use thin summary rows first. Rich movie details, series seasons, and episodes hydrate only when the user opens or plays an item. Existing Xtream providers and already-synced data are preserved and backfilled into the new index model.

Xtream endpoint assumptions are based on the common Player API shape: `get_live_streams`, `get_vod_streams`, `get_series`, category endpoints, `get_vod_info`, `get_series_info`, `get_short_epg`, and XMLTV.

Reference docs:

- https://servextex.github.io/xtream-ui-ubuntu/player_api_documentacion.html
- https://xtream-masters.com/api-doc/player_api.php
- https://ottpanel.tv/player_api.html

## Phases

### Phase 0: Plan Document

- Create `docs/xtream-index-sync-plan.md` with this phased plan.
- Treat this document as the source of truth for later edits before implementation starts.
- Scope this redesign to Xtream only. Do not redesign M3U or Stalker behavior in this project.

### Phase 1: Data Model And Migration

- Add a dedicated Xtream content index table for `LIVE`, `MOVIE`, and `SERIES` summaries.
- Store, at minimum: provider id, content type, remote id, optional local content id, name, category id/name, image URL, rating, added/modified timestamp, adult flag, indexed timestamp, detail hydrated timestamp, stale/error state, and sync fingerprint.
- Store image URLs only, not poster/logo bytes. Poster/logo files remain lazy-loaded by the app image loader/cache when rows are displayed.
- Track index job state per provider and section (`LIVE`, `MOVIE`, `SERIES`, `EPG`) with states: `IDLE`, `QUEUED`, `RUNNING`, `PARTIAL`, `SUCCESS`, `FAILED_RETRYABLE`, `FAILED_PERMANENT`, and `STALE`.
- Store progress counters where applicable: total categories, completed categories, failed categories, indexed rows, skipped malformed rows, deleted/pruned rows, last error, last attempt time, and last success time.
- Keep existing `channels`, `movies`, `series`, and `episodes` tables as the cached playable/detail tables.
- For VOD/series, also upsert summary rows into existing `movies`/`series` tables so current navigation, favorites, playback history, and repository APIs keep stable local ids.
- The separate index table is the source for cross-content search, section freshness, and indexing status. The existing content tables remain the source for current screen APIs, stable local ids, favorites, playback history, and detail/playback caching.
- Add row state fields to VOD/series cached rows: `SUMMARY_ONLY`, `DETAIL_HYDRATED`, `STALE`.
- Backfill the new index from existing Xtream `channels`, `movies`, and `series` rows during migration.
- Preserve existing provider rows, favorites, playback history, watch progress, hidden categories, protected categories, and EPG mappings.
- Batch DB writes during migration/backfill to avoid long UI-blocking transactions and reduce WAL growth.
- Add maintenance support for pruning orphan index rows whose provider/local content rows were deleted outside normal sync.

### Phase 1.5: Compatibility Semantics

- Preserve old Xtream provider records and translate old sync settings into the new model.
- Treat `xtreamFastSyncEnabled` as migration-only compatibility state. Existing `true` and `false` values should both map to the new index-first behavior.
- Alias old `VodSyncMode` values into new section/index status:
  - `FULL`, `CATEGORY_BULK`, and `PAGED` become "indexed/hydrated from legacy catalog" when local rows exist.
  - `LAZY_BY_CATEGORY` becomes "categories available, index pending/stale".
  - `UNKNOWN` becomes "index pending" unless local rows can be backfilled.
- Existing cached VOD/series rows should be kept and marked `DETAIL_HYDRATED` when they contain rich fields, otherwise `SUMMARY_ONLY`.
- Existing category hydration metadata should not be trusted as the new indexing source of truth, but it may be used to seed freshness hints.
- Do not force users to re-add providers or manually rebuild libraries after migration.
- Never clear existing VOD/series data at the start of migration or indexing.
- During incremental sync and migration, remote-missing rows should be marked stale first, not deleted immediately.
- Do not prune rows referenced by active playback history, favorites, custom groups, or in-progress playback unless the user explicitly deletes/clears data.
- When a remote id reappears, revive the existing local row and preserve local id, favorites, history, and watch progress.

### Phase 2: New Xtream Setup Flow

- Remove the setup-time Fast Sync toggle for Xtream.
- Replace `xtreamFastSyncEnabled` behavior with the new default index-first flow.
- Xtream add-provider should block only on:
  - authentication,
  - live categories,
  - full live channel catalog,
  - VOD categories,
  - series categories.
- After that, mark the provider active or partial and return to the app.
- Queue background work for:
  - VOD summary index from `get_vod_streams`,
  - series summary index from `get_series`,
  - EPG sync according to provider EPG mode.
- VOD/series index jobs should stream/parse large Xtream list responses where possible and commit summary rows in batches. Do not wait for the entire response to finish before making rows visible.
- If unfiltered VOD/series list endpoints fail, are too large, or time out, fall back to background category-by-category indexing without blocking setup.
- Live TV remains fully loaded upfront because `get_live_streams` already returns the usable live catalog needed for playback, search, EPG mapping, catch-up flags, and Android TV input sync.
- Failure policy during setup:
  - Auth failure marks provider setup failed/permanent and does not queue indexing.
  - Response-too-large on VOD/series full-list indexing marks that strategy unavailable and queues category fallback.
  - Network/timeouts mark queued background work retryable and preserve any setup data already saved.
- Setup must not delete existing catalog/index rows for an edited existing provider unless the user explicitly deletes the provider.
- Setup should publish queued/running section state so Settings/Search/VOD can immediately show that VOD/series indexing is in progress.

### Phase 3: Browse, Search, And Detail Hydration

- Search should return live, movie, and series results from the summary index / summary-backed local rows.
- Search must run against all indexed rows for the provider/scope, not only rows visible in the currently loaded UI page or preview batch.
- Search result pagination should page the ranked indexed result set. Loading more search results must not require opening categories.
- If indexing is still in progress, search should return all currently indexed matches and expose an indexing/partial state so the UI can distinguish "still indexing" from "no results".
- If a provider falls back to category-by-category indexing, matching items become searchable as their categories are indexed. The global search surface should update incrementally.
- VOD and series category screens should show indexed summary rows immediately when available.
- Category click should not trigger full rich hydration. It may trigger a background freshness check for that category.
- Category pagination should page summary rows from the index/local summary rows first.
- If a category has not been indexed yet, clicking it should enqueue/prioritize that category's index job and show a loading/partial state, not an empty "no content" state.
- If global VOD/series indexing is already running, user-selected categories should be moved to the front of the remaining index queue without cancelling successfully indexed work.
- Search cards and category cards should show poster/logo images when the summary row has an image URL. If the URL is blank or fails, show the existing placeholder. Later detail hydration may replace the image URL and refresh the UI.
- Movie detail open should call `get_vod_info`, merge richer fields into the cached movie row, and mark it `DETAIL_HYDRATED`.
- Series detail open should call `get_series_info`, merge richer series fields, persist seasons/episodes, and mark it `DETAIL_HYDRATED`.
- Movie playback may use the indexed stream id and container extension to build the internal playback URL. If required playback fields are missing, hydrate details first.
- Series playback should require series detail hydration because episodes come from `get_series_info`.
- Existing continue watching, favorites, related content, and recommendations should continue using local cached rows, now backed by summary-first data.
- Detail freshness rules:
  - Movie details and series details hydrate on open/play.
  - Details should not be refreshed on every open.
  - Default detail refresh TTL: 14 days, unless the user manually refreshes, the item is stale/missing required playback fields, or the provider reports a newer modified timestamp.
- Detail hydration failures should keep summary rows usable and mark only the detail state failed/retryable.
- Empty search while indexing is active should say the library is still indexing rather than implying no content exists.
- Category browse should expose retry affordance if that category failed to index.
- Search/category screens should not trigger destructive pruning. They may only enqueue/prioritize index or detail work.

### Phase 4: Settings Sync Redesign

- Replace Xtream Full/Fast sync options with:
  - `Sync Now`: incremental sync for stale live, categories, VOD index, series index, and EPG.
  - `Rebuild Index`: rebuild VOD/series summary indexes from Xtream, preserving hydrated detail rows where remote ids still exist.
  - `Live TV`: refresh live categories and full live channel catalog.
  - `Movies`: refresh movie categories and movie summary index.
  - `Series`: refresh series categories and series summary index.
  - `EPG`: enqueue or run EPG sync.
- Remove foreground full VOD import and fast lazy category-only as user-facing Xtream modes.
- Keep internal recovery strategies only as background indexing fallbacks, not as separate product modes.
- Settings progress should report background-safe states: queued, indexing, indexed count, stale, partial, failed, retrying.
- Manual section syncs should use the same index-first pipeline as setup and background sync.
- Manual `Rebuild Index` should mark rows missing from the latest successful section scan as `STALE_REMOTE`, but prune only after a complete successful rebuild confirms absence.
- Rebuild Index must preserve hydrated details for unchanged remote ids and preserve stable local ids whenever possible.
- Section sync failures from Settings should preserve previous visible content and update section status/error metadata.
- Settings must show enough state to distinguish queued, running, partial, stale, retryable failed, and permanent failed work.

### Phase 5: Background Sync And Staleness

- Reuse WorkManager as the scheduling backbone.
- Add or refactor workers so Xtream catalog sync can run by section: live, movie index, series index, EPG.
- On app launch, enqueue a non-blocking stale check for active Xtream providers.
- Periodic provider sync should:
  - refresh stale live catalog,
  - refresh stale VOD/series indexes,
  - refresh EPG when due,
  - avoid blocking UI startup.
- Default TTLs:
  - live catalog: 24 hours,
  - VOD summary index: 24 hours,
  - series summary index: 24 hours,
  - EPG: 6 hours,
  - detail hydration: refresh only on explicit open if stale or missing.
- Use network-connected constraints, exponential backoff, retryable failure classification, and low-memory deferral where relevant.
- Partial failures should preserve previous indexed data and mark the failed section stale/error instead of clearing user-visible libraries.
- Index workers should publish progress after each committed batch: indexed count, current section, optional current category, and whether results are complete or partial.
- Index workers should use batch commits so search/category UI can observe newly indexed rows while long VOD/series indexing continues.
- Index freshness:
  - Live catalog TTL: 24 hours.
  - Movie summary index TTL: 24 hours.
  - Series summary index TTL: 24 hours.
  - EPG TTL: 6 hours.
- Failure policy:
  - HTTP 401/403 on auth/root catalog requests is permanent until credentials/settings change.
  - HTTP 408/409/429/5xx, network loss, DNS failure, connection reset, and timeout are retryable.
  - Malformed whole-response JSON marks the section partial/failed and keeps old data.
  - Malformed individual streamed items are skipped, counted, and do not stop the whole job.
  - Empty valid results do not delete old rows unless a complete successful rebuild confirms true absence.
- Deletion policy:
  - Incremental sync may mark missing rows `STALE_REMOTE`.
  - Only complete successful rebuilds may prune remote-missing rows.
  - Partial/category indexing may only update stale status for categories that completed successfully.
- Concurrency policy:
  - Default to one heavy Xtream indexing request per provider at a time.
  - Do not run VOD and series full-list heavy indexing in parallel for the same provider by default.
  - Use adaptive concurrency only for category fallback, starting conservatively and reducing to sequential after rate limits, timeouts, response-too-large, or repeated provider stress.
  - Do not let background indexing compete with active playback for network/CPU. If needed, pause/defer non-urgent indexing during playback.

### Phase 6: Cleanup And Compatibility

- Deprecate/remove Xtream full/category-bulk/paged foreground sync branches once the new index pipeline is stable.
- Remove `LAZY_BY_CATEGORY` as a user-facing Xtream outcome.
- Keep compatibility aliases in metadata migrations so old providers do not break.
- Update setup and settings strings to remove old Fast Sync language.
- Keep M3U and Stalker sync behavior unchanged.
- After implementation, run `graphify update .` because code files will have changed.

## Acceptance Criteria

- Adding an Xtream provider completes after auth, live catalog, VOD categories, and series categories, without waiting for full VOD/series indexing.
- Live TV is playable and searchable immediately after setup succeeds.
- VOD/series categories appear immediately after setup succeeds.
- VOD search finds items from unvisited categories after the summary index completes.
- If indexing is still running, search and category screens clearly show partial/indexing state.
- Opening a movie hydrates rich details once, persists them, and does not refetch on every open.
- Opening a series hydrates seasons/episodes, persists them, and preserves local ids on refresh.
- Existing Xtream providers migrate without re-adding credentials and preserve favorites, history, watch progress, protected/hidden categories, and EPG mappings.
- Background sync never blocks app launch.
- Failed refreshes preserve previous visible content.
- Rebuild Index can repair stale/bad index state without wiping user data on partial failure.

## Public API / Type Changes

- Add index status models for summary indexing and detail hydration.
- Replace Xtream setup command usage of `xtreamFastSyncEnabled` with an index-first default; keep the stored field only as migration compatibility until later cleanup.
- Extend sync metadata with last-success/attempt fields for live index, movie index, series index, and per-section stale/error state.
- Add sync commands for `SYNC_NOW`, `REBUILD_INDEX`, `LIVE`, `MOVIES`, `SERIES`, and `EPG`.
- Add repository behavior for summary-backed VOD/series rows and detail hydration on open/play.

## Test Plan

- Migration tests:
  - Existing Xtream movie/series/channel rows backfill index rows.
  - Favorites, history, watched progress, and protected categories survive migration.
- Setup tests:
  - Xtream add-provider completes after live + categories and queues background VOD/series/EPG work.
  - VOD list endpoint failure falls back to queued category indexing without failing provider setup.
- Indexing tests:
  - `get_vod_streams` summaries upsert movie summary rows and searchable index rows.
  - `get_series` summaries upsert series summary rows and searchable index rows.
  - Rebuild index preserves hydrated details for unchanged remote ids.
- Browse/search tests:
  - Search finds unvisited VOD/series categories after summary indexing.
  - Category screens show summary rows without rich detail hydration.
  - Live search remains available after live catalog sync.
- Detail tests:
  - Opening movie detail calls `get_vod_info` once when missing/stale and persists enriched fields.
  - Opening series detail calls `get_series_info`, persists episodes, and marks detail hydrated.
  - Playback hydrates first only when required playback fields are missing.
- Background tests:
  - Launch stale check enqueues work without blocking UI.
  - Periodic sync refreshes only stale sections.
  - Retryable failures use WorkManager retry; permanent auth/config failures mark section error.
- UI tests:
  - Setup no longer shows Xtream Fast Sync.
  - Settings shows Sync Now, Rebuild Index, Live TV, Movies, Series, and EPG.
  - Sync progress/status reflects queued/indexing/partial states.

## Assumptions And Defaults

- Xtream-only redesign; do not alter M3U or Stalker sync architecture.
- Existing local content is preserved and indexed, not wiped.
- Live TV loads full usable channel rows upfront.
- Live TV still participates in the shared content index for search/status, but the live channel table remains the usable playback catalog.
- VOD/series rich details hydrate on open/play, not for the entire library in background.
- Background category-by-category indexing is a fallback for providers whose full VOD/series list endpoints fail.
