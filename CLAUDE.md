# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build + run unit/integration tests (Layer 1+2), enforce JaCoCo coverage
mvn clean verify

# Build + E2E tests (requires Chrome installed)
mvn clean verify -Pe2e

# Skip tests during build
mvn clean package -DskipTests

# Run a single test class
mvn test -Dtest=JsonlWriterTest

# Run a single test method
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush

# Release to Maven Central (requires GPG key + credentials)
mvn clean deploy -Prelease
```

Requirements: JDK 17+, Maven 3.8+. Maven enforcer will fail the build otherwise.

JaCoCo enforces a minimum line coverage of **84%** (`jacoco.line.coverage.minimum` in pom.xml) on a bundle that excludes `gui/**`, `BpmListener`, `ChromeCdpCommandExecutor`, and `CdpSessionManager` — these require a live JMeter/CDP environment.

## Architecture

BPM is a JMeter listener plugin that hooks into the `SampleListener` / `TestStateListener` lifecycle to capture browser-side performance metrics from WebDriver Sampler executions using Chrome DevTools Protocol (CDP).

### Data Flow

```
WebDriver Sampler executes
        ↓
BpmListener.sampleOccurred()
  → Extracts ChromeDriver via Class.forName() (lazy, avoids hard Selenium dep)
  → CdpSessionManager.openSession() — enables CDP domains + injects JS observers
  → CdpSessionManager.transferBufferedEvents() — drains browser-side JS buffers into MetricsBuffer
  → CdpSessionManager.ensureObserversInjected() — re-injects after page navigation
  → Collectors run: WebVitalsCollector, NetworkCollector, RuntimeCollector, ConsoleCollector
  → DerivedMetricsCalculator computes score, bottleneck, ratios
  → BpmResult assembled → JsonlWriter writes to .jsonl file
  → BpmListenerGui.addRow() updates live Swing table (GUI mode only)
        ↓
BpmListener.testEnded()
  → SummaryJsonWriter writes bpm-results-summary.json (currently disabled — see Feature #1)
  → Log summary printed
```

### Package Structure

| Package | Responsibility |
|---|---|
| `core` | `BpmListener` (main entry point), `CdpSessionManager` (CDP lifecycle), `MetricsBuffer` (thread-local event staging), `ChromeCdpCommandExecutor` (Selenium wrapper), `CdpCommandExecutor` (interface) |
| `collectors` | `MetricsCollector<T>` interface + four implementations (WebVitals, Network, Runtime, Console), `DerivedMetricsCalculator` |
| `model` | Plain data classes: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `ResourceEntry` |
| `config` | `BpmPropertiesManager` — reads/writes `bpm.properties`, handles version migration, exposes all SLA thresholds and feature toggles |
| `output` | `JsonlWriter` (buffered JSONL, flush every 10 records), `SummaryJsonWriter`, `CsvExporter` |
| `gui` | `BpmListenerGui` (Swing table + controls), `ColumnSelectorPopup` |
| `util` | `JsSnippets` (CDP JavaScript constants), `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger` |
| `error` | `BpmErrorHandler`, `LogOnceTracker` |

### Key Design Decisions

- **No hard Selenium dependency at class load time**: `BpmListener` uses `Class.forName()` to detect ChromeDriver, so the plugin loads without Selenium on the classpath.
- **All Selenium code isolated in `ChromeCdpCommandExecutor`**: The rest of the codebase programs against the `CdpCommandExecutor` interface.
- **JS-buffer event capture**: Instead of Selenium DevTools event APIs (which vary by version), BPM injects JavaScript hooks (`JsSnippets`) that buffer LCP, CLS, console, and network events in `window.__bpm_*` arrays. `CdpSessionManager.transferBufferedEvents()` drains them before each collection cycle.
- **`INJECT_OBSERVERS` vs `REINJECT_OBSERVERS`**: The re-inject variant resets `window.__bpm_cls = 0` to prevent CLS double-counting after a CDP session re-init. Use the regular variant after a full page navigation (where the browser already reset the accumulator).
- **Stateless collectors**: All four `MetricsCollector` implementations are stateless singletons. Mutable per-thread state lives in `MetricsBuffer`. Exception: `WebVitalsCollector` tracks previous LCP per-thread for SPA stale detection.
- **Per-thread CDP sessions**: `BpmListener` holds a `ConcurrentHashMap<String, CdpCommandExecutor>` keyed by thread name, with a matching map for `CdpSessionManager` instances.
- **All runtime deps are `provided`**: JMeter core, Selenium, and Jackson are already on the JMeter classpath at runtime; the plugin JAR ships zero additional dependencies.
- **DONT_START flag lifecycle**: `BpmListener` holds a `static volatile boolean dontStartPending`. When the file-exists dialog resolves to DONT_START, the active instance sets this flag and stops the engine; all subsequent `testStarted()` calls on clone instances early-return immediately. The flag is cleared only on successful test start or in `testEnded()`. Never reset at the top of `testStarted()` — that was the original clone-escape bug.
- **`testActuallyStarted` instance flag**: Set to `true` only when a `BpmListener` instance completes full setup in `testStarted()`. `testEnded()` skips all cleanup (flush, close, GUI notify) if this flag is `false`, preventing spurious side-effects from DONT_START or partially-initialised clone instances.
- **Cached engine reference**: `cachedEngine` is populated at the very top of `testStarted()` via `JMeterContextService.getContext().getEngine()` — before any blocking dialog. After `invokeAndWait()` returns, the ThreadLocal context may no longer carry the engine reference, so it must be captured early and passed to `stopTestEngine(cachedEngine)`.
- **`stopTestEngine()` dual-path stop**: Calls `engine.stopTest(true)` on the cached engine reference (immediate stop). Also fires an `ActionRouter.doActionNow("stop")` on the EDT as a fallback in case the cached reference is null (GUI mode only).
- **Manual-only filtering**: All filter fields (transaction names, include/exclude, start/end offset) apply only when the user clicks **Apply Filters**. No auto-trigger on focus-lost, Enter, or selection change. `applyAllFilters()` calls `rebuildTableFromRaw()` which re-evaluates the full `allRawResults` list.
- **Start/End Offset always enabled**: Both offset fields are always editable regardless of test state or table content. Only transaction name controls are disabled when the table is empty.
- **`rebuildTableFromRaw()` as single source of truth for time fields**: In file-load and post-filter mode (`!testRunning`), `rebuildTableFromRaw()` computes `firstFiltered`/`lastFiltered` instants from the filtered result set and calls `updateTimeFieldsFromRaw()` to set all three time fields and the score box. During live test, only the start field is updated from `firstFiltered` when a start offset is active; end/duration are owned by the continuous timer.
- **Continuous time update during live test**: `drainGuiQueue()` runs on a 500 ms `javax.swing.Timer`. At the top of every drain cycle, when `testRunning` is `true`, it writes `Instant.now()` to the End field and recomputes Duration — giving sub-second accuracy without waiting for `testEnded()`.
- **`testEnded()` `wasRunning` guard**: `BpmListenerGui.testEnded()` captures `wasRunning = testRunning` before resetting the flag. Time fields are only updated from `Instant.now()` when `wasRunning` is `true`. In the DONT_START path, `testStarted()` never ran so `testRunning` was never set to `true`, and the time fields are left untouched (preserving any loaded-file data).
