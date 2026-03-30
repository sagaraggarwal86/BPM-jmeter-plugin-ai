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
