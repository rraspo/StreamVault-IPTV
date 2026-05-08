# Xtream Live Onboarding Reliability Plan

## Table Of Contents

- [Summary](#summary)
- [Plan](#plan)
- [Implementation Order](#implementation-order)
- [What Improves Weak-Device Reliability](#what-improves-weak-device-reliability)
- [What Improves Add-Provider Speed](#what-improves-add-provider-speed)
- [Xtream Live Thin-Row Follow-Up](#xtream-live-thin-row-follow-up)
- [Validation Criteria](#validation-criteria)
- [Open Questions](#open-questions)

## Summary

This document captures the planned Xtream Live onboarding redesign focused on correctness first, with explicit attention to weak-device behavior and large Live catalogs.

The current risk areas are:

- provider add can depend on a long Live import completing in one uninterrupted run,
- Xtream Live finalization logic is split across multiple paths,
- slow or interrupted devices can be exposed to broken partial outcomes,
- provider setup can look complete even when Live import is not yet safely usable.

The main goal is to make provider add finish in a correct and durable state, especially for large Xtream Live catalogs. A secondary goal is to enable a later usable-first onboarding flow that can improve perceived setup speed.

## Plan

1. Stabilize the validation baseline first.
Resolve the current app compile failure so every refactor can be checked quickly with `SyncManager.kt`, `ProviderRepositoryImpl.kt`, and related tests. This is not part of the provider-add fix itself, but without a clean compile loop the rest will be slower and riskier.

2. Define the Live onboarding invariants in the sync layer.
Write down and enforce the rules the code must satisfy before provider add is considered complete:
- Xtream Live onboarding must not finish in a state where Live categories are committed but usable Live channels are not.
- A staged full-catalog success must commit staged rows, not depend on any empty in-memory fallback list.
- Category-fallback success, partial success, and full success must all flow through one commit contract.
This work belongs around `SyncManager.kt`, `SyncManagerXtreamLiveStrategy.kt`, and `SyncCatalogStore.kt`.

3. Extract one shared Xtream Live commit pipeline.
Create a single internal helper that owns all Xtream Live finalization behavior:
- interpret `stagedSessionId`
- merge visible and hidden categories
- choose replace versus upsert semantics
- apply staged imports or direct imports
- return accepted count and final sync warnings
Both the initial onboarding path and the Live-only retry path should call this same helper instead of having separate success logic in `SyncManager.kt`.

4. Refactor the initial add-provider flow to use the shared Live commit pipeline.
Keep the current Xtream strategy selection and staging logic, but route onboarding completion through the same commit helper as retries. The immediate goal is that a slow device, fallback path, or staged full import cannot end in a categories-only Live state during provider add.

5. Make initial Live import resumable instead of fragile.
Treat provider add as a resumable onboarding import job:
- persist enough onboarding state to know whether initial Live import is still in progress
- retain staged Live import data until committed or explicitly discarded
- resume interrupted onboarding after process death or app restart
Only this step materially improves weak-device reliability, because it removes the assumption that provider add must finish in one uninterrupted foreground run.

6. Separate provider persistence from provider usability.
Keep saving provider credentials and metadata, but do not expose the provider as fully active/ready until the initial Live import reaches the invariant from step 2. This means changing behavior in `ProviderRepositoryImpl.kt` so “saved” and “usable” are not treated as the same thing.

7. Adjust onboarding UI only after the backend flow is correct.
Once the sync path is reliable, update `app/src/main/java/com/streamvault/app/ui/screens/provider/ProviderSetupViewModel.kt` so the screen reflects real import state:
- initial import in progress
- initial import resumable
- provider ready
- provider saved but still completing import
This is not the core fix, but it needs to align with the backend once the backend semantics change.

8. Add hard regression tests around the real failure modes.
Cover these cases:
- initial Xtream onboarding full staged success commits staged Live rows
- initial Xtream onboarding category fallback commits usable Live rows
- provider add cannot finish ready with Live categories and zero committed Live channels unless the source is explicitly empty
- interrupted onboarding resumes safely
- retry and onboarding both use the same Live commit path
Target files are `SyncManagerTest.kt`, `ProviderSetupViewModelTest.kt`, and any focused new sync tests if the current file gets too large.

9. Add diagnostic logging around provider-add Live import.
Log only what is needed to diagnose real-device failures:
- full versus category fallback chosen
- category count
- accepted Live row count
- staged session created, applied, discarded
- elapsed time for each major phase
- resume versus fresh onboarding
This is not a workaround. It is necessary to verify whether the final design actually fixes Chromecast-class failures.

10. Validate on both strong and weak environments.
Run validation in this order:
- local focused sync tests
- targeted compile checks
- emulator onboarding with a very large Xtream provider
- real Chromecast or comparable low-resource TV device onboarding with the same provider
The success criterion is not just “no error shown.” It is:
- provider add completes
- Live categories appear
- Live counts are non-zero when provider data is non-empty
- app survives slow import or resumes cleanly if interrupted

## Implementation Order

1. Fix compile baseline.
2. Extract shared Xtream Live commit helper.
3. Switch onboarding and retry paths to the shared helper.
4. Introduce resumable initial Live onboarding state.
5. Separate saved versus ready provider semantics.
6. Update onboarding UI contract.
7. Add regression tests.
8. Validate on emulator and real weak device.

## What Improves Weak-Device Reliability

These are the parts of the plan that directly help weaker or interruption-prone devices:

1. Resumable initial Live import.
This is the largest reliability improvement. Slow devices are more likely to be interrupted, paused, backgrounded, or reclaimed during a long provider-add run.

2. Shared, correct Xtream Live commit logic.
Weak devices are more likely to exercise fallback and staged paths. A single commit pipeline reduces the chance of invalid final state under stress.

3. Finalize provider readiness only after a valid Live state.
This prevents slow devices from exposing categories-with-zero-channels as if setup completed successfully.

4. Proper use of staged import for onboarding.
This reduces the damage caused by long-running work, interruptions, and alternate strategy paths.

## What Improves Add-Provider Speed

The main plan above is reliability-first. These additions are the ones that would make add-provider feel faster for Live TV:

1. Make onboarding usable-first instead of full-library-first.
Authenticate, import Live categories, import an initial usable Live slice, then let the user into Live TV while the rest continues in background.

2. Separate provider-added from full Live hydration.
Provider setup should complete when the provider is usable, not only when every Live row is imported.

3. Commit the first usable staged slice early.
Do not wait for the full Live catalog when a meaningful first batch can make browsing possible.

4. Defer noncritical work out of the setup critical path.
Anything not required for initial Live browse should not block add-provider completion.

5. Keep resumability even in the faster flow.
Usable-first onboarding still needs resumable import so weak devices do not restart large work from zero.

## Xtream Live Thin-Row Follow-Up

Xtream Live row thinning is a valid follow-up optimization, but it is not the first fix for the current onboarding failure mode.

The sequencing decision is:

1. First fix correctness and durability.
Shared commit logic, resumable onboarding, and correct ready-state semantics matter more than row width for the reported provider-add failure.

2. Then optimize Xtream Live storage for the hot path.
If add-provider speed is still not good enough after the reliability work, Xtream Live can move toward a thinner persisted row shape.

3. Prefer a compatibility-preserving implementation first.
The safest first version is to keep the current `channels` table schema and have Xtream fast sync populate only the core columns during onboarding, while deferring noncritical fields until later hydration.

The practical thin-row guidance for Xtream Live is:

- keep as core: `providerId`, `streamId`, `name`, `categoryId`, `epgChannelId`, `logoUrl`, `number`, `isAdult`, minimal catch-up flags, compact `syncFingerprint`
- prefer to derive or defer: `categoryName`, full playable `streamUrl`, quality metadata, playback/runtime error stats
- keep M3U-oriented fields such as `groupTitle` out of the Xtream fast-sync hot path unless a shared schema requires them

Compatibility guidance:

1. App-update compatibility can be preserved.
If the thinning work is done with normal Room migration support, users updating from older versions can be migrated safely.

2. Old-app downgrade compatibility should not be assumed.
Once a newer version changes the database schema, an older app version usually will not be able to reopen that upgraded database unless explicit downgrade support is built.

3. Deferred-population on the existing schema is the lowest-risk path.
This approach improves Xtream add-provider performance without forcing an immediate schema split across Xtream, M3U, Stalker, player, and preference code.

## Validation Criteria

The final design should satisfy all of the following:

- adding a provider cannot finish in a categories-only Live state when the source has usable Live channels,
- staged full Live success and category fallback both produce valid committed Live rows,
- interrupted onboarding either resumes or stays clearly incomplete instead of silently breaking the provider,
- weak devices do not require special-case logic to avoid invalid final state,
- if a usable-first onboarding path is added, time-to-first-usable-Live improves without sacrificing catalog correctness.

## Open Questions

1. Should provider add remain blocked on full Live hydration, or should the product explicitly move to a usable-first Live onboarding model?
2. What minimum Live threshold should count as “usable” if setup is allowed to complete before full hydration?
3. Should interrupted onboarding auto-resume on next launch, or require an explicit user confirmation when the provider is saved but incomplete?
4. How much telemetry is acceptable in release builds for diagnosing slow-device onboarding failures without creating noisy logs?
5. If add-provider speed becomes a product priority, should category priority be based on provider ordering, first visible categories, or a fixed bootstrap slice such as All Channels plus the first N categories?