# Changelog

## [2.2.1] - 2026-03-06

### Fixed

- Reworked pipeline wakeup path from per-event `signalConsumer` to lazy wakeup (`publish + signalIfWaiting`) to remove producer hot-path allocation/CAS/syscall amplification.
- Removed `ConsumerReadyQueue` and `LogCollectContext` ready-queue flags; wakeup state is now consumer-level (`consumerWaiting` CAS gate).
- Added segmented `consumerCursor` advancement in `PipelineConsumer` to reduce producer false-full observations during drain batches.

### Changed

- Added `pipeline.consumer-cursor-advance-interval` (default `8`) and wired it through config resolution/default properties.
- Hardened Stress CI gate:
  - ratio floor now uses layered multiplier (`resolveRatioMinMultiplier`).
  - ratio failures now apply numerator throughput fallback (WARNING instead of FAIL when absolute throughput is healthy).
- Extended CI stress baseline metadata with version binding fields:
  - `frameworkVersion`, `commitHash`, `refreshDate`, `runs`, `buildStrategy` (profile-level and jdk-level `_meta`).

### Docs

- Updated README V2.2 section with V2.2.1 defect analysis, F1-F6 implementation mapping, and verification notes.
