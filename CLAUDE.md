# CLAUDE.md

## Working Rules

- Target JMeter 5.6.3 exclusively — verify all APIs, interfaces, and classes exist in 5.6.3 before using them
- Never change git history or Java 17 implementation
- Never assume — ask if in doubt
- Never make changes to code until user confirms
- Never change existing functionality or make changes beyond confirmed scope
- Only recommend alternatives when there is a concrete risk or significant benefit
- Analyze impact across dependent layers (collector → model → derived → GUI → output) before proposing changes
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**

## Build Commands

```bash
mvn clean verify                          # Build + tests + JaCoCo coverage check
mvn clean verify -Pe2e                    # Build + E2E tests (requires Chrome)
mvn clean package -DskipTests             # Build only
mvn test -Dtest=JsonlWriterTest           # Single test class
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush  # Single test method
mvn clean deploy -Prelease                # Release to Maven Central
```

Requirements: JDK 17 only, Maven 3.8+. JaCoCo enforces **84%** line coverage excluding `gui/**`, `ai/**`, `cli/**`,
`BpmListener`, `ChromeCdpCommandExecutor`, `CdpSessionManager`, `BpmTimeBucket`.

## Architecture

JMeter listener plugin capturing browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver
Sampler via Chrome DevTools Protocol. Includes optional AI-powered analysis reports.

### Package Structure

| Package       | Responsibility                                                                                                                                                |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core`        | `BpmListener` (entry point, SampleListener/TestStateListener), `CdpSessionManager` (CDP lifecycle), `MetricsBuffer`, `LabelAggregate`                         |
| `collectors`  | `MetricsCollector<T>` + 4 implementations (WebVitals, Network, Runtime, Console), `DerivedMetricsCalculator`                                                  |
| `model`       | Data records: `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `BpmTimeBucket`                            |
| `config`      | `BpmPropertiesManager` — SLA thresholds, feature toggles, `bpm.properties` management                                                                         |
| `output`      | `JsonlWriter` (buffered, flush every 10), `SummaryJsonWriter`, `CsvExporter`                                                                                  |
| `gui`         | `BpmListenerGui` (Swing table + AI report button), `ColumnSelectorPopup`, `TotalPinnedRowSorter`                                                              |
| `ai.provider` | `AiProviderRegistry` (7 providers, shared `ai-reporter.properties`), `AiProviderConfig`, `AiReportService` (OpenAI-compatible API client), `SharedHttpClient` |
| `ai.prompt`   | `BpmPromptBuilder` (pre-computed verdicts + trends → JSON), `BpmPromptLoader`, `TrendAnalyzer`, `BpmPromptContent`                                            |
| `ai.report`   | `BpmHtmlReportRenderer` (CommonMark + Chart.js), `BpmAiReportCoordinator`, `BpmAiReportLauncher`                                                              |
| `cli`         | `Main` (entry point), `CliArgs`, `BpmCliReportPipeline`, `TimeBucketBuilder`                                                                                  |
| `util`        | `JsSnippets`, `ConsoleSanitizer`, `BpmConstants`, `BpmDebugLogger`                                                                                            |
| `error`       | `BpmErrorHandler`, `LogOnceTracker`                                                                                                                           |

### Key Design Decisions

- **No hard Selenium dep**: `BpmListener` uses `Class.forName()` to detect ChromeDriver. All Selenium code isolated in
  `ChromeCdpCommandExecutor`; rest uses `CdpCommandExecutor` interface.
- **JS-buffer capture**: Injects JS hooks (`JsSnippets`) that buffer events in `window.__bpm_*` arrays.
  `CdpSessionManager.transferBufferedEvents()` drains them. `REINJECT_OBSERVERS` resets CLS accumulator;
  `INJECT_OBSERVERS` does not.
- **Stateless collectors**: All four collectors are stateless singletons. Per-thread state in `MetricsBuffer`.
  Exception: `WebVitalsCollector` tracks previous LCP for SPA stale detection.
- **All runtime deps `provided`**: JMeter core, Selenium, Jackson on JMeter classpath. Only CommonMark is shaded (
  relocated to `io.github.sagaraggarwal86.shaded.commonmark`).
- **Clone delegation**: JMeter's `AbstractTestElement.clone()` creates new instances via the no-arg
  constructor — transient fields (testInitialized, guiUpdateQueue, rawResults, jsonlWriter) are NOT shared.
  Per-thread clones delegate `sampleOccurred()` to the primary registered in `primaryByName`.
  Only the primary (execution-tree element that ran `testStarted()`) owns mutable state.
- **Execution tree ≠ GUI tree**: JMeter creates a separate execution tree for test runs. `testStarted()`
  runs on execution-tree elements (different object identity from GUI-tree elements). `configure()` receives
  GUI-tree elements. `gui.testEnded()` must persist rawResults from the execution primary to the GUI element
  via `setRawResults()` so data survives post-test `configure()` calls.
- **Aggregate Report pattern**: GUI follows JMeter's `StatVisualizer` design — data lives in the TestElement
  (`BpmListener.rawResults`), GUI reads from it unconditionally in `configure()`. Timer runs forever with
  direct model updates (`addOrUpdateResult` + `fireTableDataChanged`), no queue-drain-rebuild. Full
  `rebuildTableFromRaw()` only on filter change or file load. Column visibility persisted in TestElement
  properties, not static caches.
- **Pre-flight file scan**: First primary wins `preFlightDone.compareAndSet(false, true)`, scans ALL enabled
  BpmListeners via `GuiPackage.getTreeModel().getNodesOfType()`. Single dialog lists all conflicts. Decision cached in
  `globalFileDecision` (OVERWRITE/DONT_START). CLI always overwrites.
- **Output path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default `bpm-results.jsonl`.
- **`pendingFreshClear`**: `createTestElement()` strips properties + sets flag. `configure()` clears display and returns
  early for new elements.
- **`testActuallyStarted`**: Instance flag set only after full `testStarted()` setup. `testEnded()` skips cleanup if
  false.
- **`cachedEngine`**: Captured at top of `testStarted()` before blocking dialog. `stopTestEngine()` uses cached ref +
  ActionRouter fallback.
- **Manual-only filtering**: Filters apply only on "Apply Filters" button click. `rebuildTableFromRaw()` is single
  source of truth.

### AI Analysis Architecture

- **Java does ~95% of work**: Performance Metrics table, SLA Compliance verdicts, Critical Findings (diagnosis +
  actions),
  Performance Trends (6 charts with per-label filter), pagination, sorting, search — all Java-generated.
- **AI does ~5%**: Generates 3 sections of narrative prose (Executive Summary, Recommendations, Risk Assessment).
- **7 providers**: groq, gemini, mistral, deepseek, cerebras, openai, claude. Shared `ai-reporter.properties` config.
- **CLI workflow** (two-step):
  ```
  jmeter -n -t test.jmx -Jbpm.output=results.jsonl    # Step 1: run test
  bpm-ai-report -i results.jsonl --provider groq        # Step 2: generate AI report
  ```
- **Prompt design**: 8 absolute rules, 3 sections with audience-specific templates, 9 edge cases, trend analysis with
  pre-computed direction/alerts. System prompt in `src/main/resources/bpm-ai-prompt.txt`.
- **HTML report panels** (7 total):
    1. Executive Summary (AI) — non-technical stakeholder overview
    2. Performance Metrics (Java) — full data table with pagination, sorting, search
    3. Performance Trends (Java) — 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render Time) with SLA lines +
       per-label filter
    4. SLA Compliance (Java) — verdict matrix with Pass/Warning/Fail per metric, search
    5. Critical Findings (Java) — only transactions needing attention, with root cause + recommended action
    6. Recommendations (AI) — improvement area table with affected transactions and priority
    7. Risk Assessment (AI) — headroom, boundary, cross-page pattern, trend risks
- **Report features**: sidebar navigation, metadata grid, page-based pagination + column sorting on all tables,
  transaction search, Excel export (SheetJS), print/PDF CSS, save dialog (JFileChooser), Chart.js CDN.

### Key Constraints

- `performanceScore` is `Integer` (nullable) everywhere — unboxing `null` to `int` will NPE and silently abort JSONL
  writes.
- `BpmConstants` is the single source of truth — never hardcode column indices, label strings, or property keys outside
  it.
- JSONL schema (`DerivedMetrics`, `BpmResult` `@JsonProperty` names) is public and backward-compatible — field renames
  are breaking changes.
- `BpmConstants.TEST_ELEMENT_*` key strings are stored in `.jmx` files — renaming breaks existing test plans.
- Selenium types confined to `ChromeCdpCommandExecutor` — lazy class loading via `Class.forName()`.
- Chrome-only via CDP — acknowledged constraint, documented not hidden.
- Pure observer — never crashes the test; all exceptions caught; graceful degradation.
- UI preserves `AbstractListenerGui` and `Clearable` contracts.
- `TotalPinnedRowSorter` must pin TOTAL to last view row for all sort directions.

### Reference Architecture — JMeter Aggregate Report (`StatVisualizer`)

When making GUI/state management decisions, refer to JMeter's Aggregate Report (`StatVisualizer` + `ResultCollector`)
as the proven reference implementation:

- **Data ownership**: Data lives in the TestElement, not the GUI. GUI reads from the element unconditionally.
- **`configure()`**: Always sync GUI with element data — no `switchedElement` guards or conditional rebuilds.
- **Timer**: Start once in constructor, never stop. Use a `volatile boolean dataChanged` flag — idle ticks cost nothing.
- **Model updates**: Update aggregate rows in-place; timer just calls `fireTableDataChanged()`. Full rebuild only on
  filter changes.
- **Clone delegation**: `AbstractTestElement.clone()` creates new instances — transient state is NOT shared.
  Per-thread clones must delegate to the primary (tree element). `ResultCollector` uses a static `FileEntry` map.
- **Per-element settings**: Persist in TestElement properties via `modifyTestElement()`/`configure()`, not static caches.
