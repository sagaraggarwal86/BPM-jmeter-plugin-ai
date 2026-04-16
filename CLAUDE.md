# CLAUDE.md

## Rules

**Behavioral**
- Never assume — ask if in doubt.
- Never edit code until the user confirms.
- Never expand scope beyond what was confirmed.
- Recommend alternatives only when there is a concrete risk or significant benefit.
- On conflicting requirements: flag, pause, wait for decision.
- On obstacles: fix the root cause. Never bypass safety checks (`--no-verify`, `git reset --hard`, disabling tests).

**Technical**
- Target JMeter 5.6.3 exclusively. Verify every API exists in 5.6.3 before using it.
- Java 17, Maven 3.8+. Do not change these targets.
- Do not rewrite git history.
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**.
- Before proposing changes, trace impact along the dependency direction (see Architecture).

## Workflow & Communication

- Interactive — present choices one at a time unless trivial and clearly scoped.
- Multi-file changes: present all files together, note dependency order.
- Rollback: revert to the last explicitly approved file set, then ask.
- After changes: self-check for regressions, naming consistency, and rule adherence.
- Summarize confirmed state if context grows large; suggest `/compact` proactively.
- Responses: concise. No filler, no restating the request.
- Feedback: direct, not diplomatic. Flag concrete concerns even when not asked.
- Decision points: present a table and highlight the recommendation:

  | Option | Risk | Effort | Impact | Recommendation |
  |--------|------|--------|--------|----------------|

## Environment

- JDK 17, Maven 3.8+. Runtime deps (`ApacheJMeter_core` 5.6.3, `selenium-chrome-driver` 4.43.0, `jackson-databind` 2.15.3) are `provided`. CommonMark 0.28.0 + ext-gfm-tables 0.24.0 are shaded.
- Test stack: JUnit Jupiter 5.11.3 (via BOM), Mockito 5.23.0.
- Shell: bash on Windows (Unix syntax — `/dev/null`, forward slashes). Child-process spawns are fork-unstable; prefer Glob/Grep/Read/Write over `find`/`grep`/`cat`.
- UI changes cannot be exercised without a live JMeter + Chrome runtime — say so explicitly rather than claiming success.

## Build & Coverage

```bash
mvn clean verify                                   # Build + tests + JaCoCo gate
mvn clean package -DskipTests                      # Build only
mvn test -Dtest=JsonlWriterTest                    # Single test class
mvn test -Dtest=JsonlWriterTest#testWriteAndFlush  # Single test method
mvn clean deploy -Prelease                         # Release to Maven Central
```

- JaCoCo gate: ≥ **84%** bundle line coverage (`verify` phase).
- Excluded from gate (Swing / CDP runtime / CLI `System.exit` / data-only): `**/gui/**`, `**/ai/**`, `**/cli/**`, `BpmListener` (+ inner classes), `BpmCollector`, `LabelAggregate`, `FileOpenMode`, `CdpSessionManager`, `ChromeCdpCommandExecutor`, `BpmTimeBucket`.
- Report: `target/site/jacoco/index.html`.
- `maven-enforcer-plugin` requires JDK 17+ and Maven 3.8+ at the `validate` phase.

## Architecture

JMeter 5.6.3 listener plugin. Captures browser performance metrics (Core Web Vitals, network, runtime, console) from WebDriver Sampler executions via Chrome DevTools Protocol. Writes JSONL per run and drives a Swing table with SLA-coloured cells, a composite performance score, improvement-area detection, and column toggles. A separate CLI (`bpm-ai-report`) consumes the JSONL and emits a 6-panel HTML report — 4 panels are Java-generated, 2 narrative sections come from any of 7 OpenAI-compatible AI providers.

**Dependency direction (acyclic):** `util` and `model` are leaves. `config` → `util`. `error` → `util`. `output` → `model`, `util`. `collectors` → `model`, `core`, `util`. `core` → `collectors`, `model`, `error`, `config`, `output`, `util`. `ai.prompt` → `model`, `util`. `ai.provider` → `ai.prompt`, `error`. `ai.report` → `ai.prompt`, `ai.provider`, `model`, `util`. `gui` and `cli` are entry points and may import anything.

### Package inventory

| Package | Key types | Responsibility |
|---------|-----------|----------------|
| `core` | `BpmListener`, `BpmCollector`, `CdpSessionManager`, `CdpCommandExecutor`, `ChromeCdpCommandExecutor`, `MetricsBuffer`, `LabelAggregate`, `FileOpenMode` | Listener entry point (`SampleListener` + `TestStateListener` + `Clearable`), ref-counted collector singleton, CDP session lifecycle, per-thread metric buffer, per-label aggregate, file-mode enum (`OVERWRITE`/`DONT_START`). |
| `collectors` | `MetricsCollector<T>` + `WebVitalsCollector`, `NetworkCollector`, `RuntimeCollector`, `ConsoleCollector`, `DerivedMetricsCalculator` | One instance per `BpmCollector`. Per-thread state in `MetricsBuffer` + collector-owned `ConcurrentHashMap` keyed by thread name. |
| `model` | `BpmResult`, `WebVitalsResult`, `NetworkResult`, `RuntimeResult`, `ConsoleResult`, `DerivedMetrics`, `ResourceEntry`, `BpmTimeBucket` | Immutable records — JSONL schema. |
| `config` | `BpmPropertiesManager` | Loads `bpm-default.properties` → writes working-dir `bpm.properties`. SLA thresholds, metric toggles, `network.topN`, bottleneck ratios, debug, sanitize. |
| `output` | `JsonlWriter`, `SummaryJsonWriter`, `CsvExporter` | `JsonlWriter.FLUSH_INTERVAL = 1` (flush every record); supports append + overwrite. |
| `error` | `BpmErrorHandler`, `LogOnceTracker` | Per-thread state machine `HEALTHY → RE_INIT_NEEDED → DISABLED`; deduped log warnings. |
| `util` | `BpmConstants`, `JsSnippets`, `ConsoleSanitizer`, `BpmDebugLogger` | Single source of truth for column indices, TestElement keys, property names, labels, tooltips; JS hook snippets. |
| `gui` | `BpmListenerGui`, `BpmTableModel`, `BpmCellRenderer`, `TooltipTableHeader`, `ColumnSelectorPopup`, `TotalPinnedRowSorter` | Swing table, SLA-coloured cells, column toggles, TOTAL pinning, AI-report launch button. |
| `ai.prompt` | `BpmPromptBuilder`, `BpmPromptLoader`, `BpmPromptContent`, `TrendAnalyzer` | Pre-computes verdicts, trends, improvement areas → JSON payload. `MAX_AI_LABELS = 20` cap, top-by-score truncation. |
| `ai.provider` | `AiProviderRegistry`, `AiProviderConfig`, `AiReportService`, `SharedHttpClient`, `AiServiceException` | 7 providers (groq, gemini, mistral, deepseek, cerebras, openai, claude) + unlimited custom entries in shared `ai-reporter.properties`. OpenAI-compatible chat/completions. |
| `ai.report` | `BpmHtmlReportRenderer`, `BpmHtmlReportRenderer.RenderConfig`, `BpmAiReportCoordinator`, `BpmAiReportLauncher` | CommonMark + Chart.js render. Coordinator returns `GenerateResult(html, suggestedFilename)`. |
| `cli` | `Main`, `CliArgs`, `BpmCliReportPipeline`, `TimeBucketBuilder`, `BpmParseException` | `bpm-ai-report` entry. Parse JSONL → filter → prompt → AI → HTML. Exit codes `0–5`. |

### Design decisions

- **Lazy Selenium**: `BpmCollector.checkSeleniumAvailability()` calls `Class.forName("org.openqa.selenium.chrome.ChromeDriver")` once per JVM. Volatile pair `seleniumAvailable` / `seleniumChecked` gives lock-free reads after init. Selenium types live only in `ChromeCdpCommandExecutor`; the rest of the code depends on `CdpCommandExecutor`.
- **BpmCollector singleton**: ref-counted (`acquire`/`release`) coordinator owning CDP sessions, 4 collectors, `DerivedMetricsCalculator`, and `BpmErrorHandler`. Per-thread maps (`executorsByThread`, `buffersByThread`, `iterationsByThread`) cleared in `shutdown()` when `refCount == 0`. `collectIfNeeded()` is the only per-sample entry point.
- **JS-buffer capture**: `JsSnippets.INJECT_OBSERVERS` seeds `window.__bpm_*` arrays once per page; `REINJECT_OBSERVERS` re-seeds and **resets the CLS accumulator** (used on full navigations). `CdpSessionManager.transferBufferedEvents()` drains them each tick.
- **Per-action CLS + SPA stale detection**: `WebVitalsCollector` tracks previous FCP/LCP/TTFB per thread — unchanged values are returned as `null` (SPA navigation fires no new paint event). CLS is returned as the positive delta since the previous sample; `resetThreadState` zeroes the baseline on page navigation.
- **Clone delegation**: `AbstractTestElement.clone()` invokes the no-arg ctor — transient fields (`testInitialized`, `guiUpdateQueue`, `rawResults`, `jsonlWriter`) are **not** shared. Per-thread clones delegate `sampleOccurred` to the primary in `primaryByName`. Only the primary (execution-tree element that ran `testStarted`) owns mutable state.
- **Execution tree ≠ GUI tree**: `testStarted` runs on execution-tree elements (different object identity from GUI-tree elements). `gui.testEnded()` calls `guiElement.setRawResults(primary.getRawResults())` so data survives the post-test `configure()` pass.
- **Composite element key**: `buildElementKey = TEST_ELEMENT_ID + "|" + TEST_ELEMENT_OUTPUT_PATH`. Keys `primaryByName`, so two listeners with the same name but different output paths remain distinct primaries.
- **Output-path dedup**: `primaryByOutputPath` — first primary claims a resolved path; second+ skip JSONL setup so two listeners never corrupt the same file.
- **Pre-flight file scan**: first primary wins `preFlightDone.compareAndSet(false, true)`, scans ALL enabled `BpmListener` nodes in the GUI tree, raises one dialog, caches the decision in `globalFileDecision` (`OVERWRITE` | `DONT_START`). CLI always overwrites.
- **Output-path priority**: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > `bpm-results.jsonl`.
- **CLI auto-enable**: in non-GUI mode, if `-Jbpm.output` is set, `isEnabled()` auto-enables the first disabled `BpmListener` (`cliAutoEnabled` latch); subsequent listeners stay disabled.
- **`testActuallyStarted`**: true only after full `testStarted` setup. `testEnded` skips cleanup when false (DONT_START / cancelled).
- **`cachedEngine`**: captured at the top of `testStarted` before any blocking dialog; `stopTestEngine` uses the cached ref with an `ActionRouter` fallback.
- **`pendingFreshClear`**: `createTestElement` strips properties + sets the flag; `configure` clears display and returns early for new elements.
- **DISABLED → HEALTHY recovery**: `BpmCollector.collectIfNeeded` re-probes the thread's browser variable each sample. If a fresh `ChromeDriver` is present, `errorHandler.resetThread` transitions back to HEALTHY and stale CDP refs (`VAR_BPM_DEV_TOOLS`, `VAR_BPM_EVENT_BUFFER`, `executorsByThread`, `buffersByThread`) are cleared — covers WebDriverConfig spawning a new browser after a crash.
- **Aggregate-Report GUI pattern**: data lives on the TestElement (`BpmListener.rawResults`). GUI reads unconditionally in `configure()`. Swing `Timer` runs forever with direct model updates (`tableModel.addOrUpdateResult` + `fireTableDataChanged`) — no queue-drain-rebuild. Full `rebuildTableFromRaw()` only on filter change or file load. Column visibility persisted in TestElement properties, not static caches.
- **Retroactive filtering**: GUI `allRawResults` (capped at `MAX_RAW_RESULTS = 10_000`) holds every result for the active element. `rebuildTableFromRaw()` re-applies current filter (offsets, labels, regex, include) without re-running the test.
- **Manual-only filtering**: filters apply on "Apply Filters" click only — the button is always enabled, even during test execution.
- **Clear All resets all listeners**: `BpmListenerGui.clearData()` iterates every `BpmListener` in the GUI tree (not just the active one) and clears filter properties (`START_OFFSET`, `END_OFFSET`, `TRANSACTION_NAMES`, `REGEX`, `INCLUDE`, `CHART_INTERVAL`).
- **Shading**: CommonMark + ext-gfm-tables relocated to `io.github.sagaraggarwal86.shaded.commonmark`. Signature files stripped (`*.SF`/`.DSA`/`.RSA`); `MANIFEST.MF` excluded from shaded artifacts so the plugin JAR's own manifest wins.

### AI report pipeline

- **Java does ~95%**: Performance Metrics table (paginated, sortable, searchable), 6 Chart.js trend charts (Score, LCP, FCP, TTFB, CLS, Render Time — each with SLA lines + per-label filter), SLA Compliance verdicts, Critical Findings (root cause + impact + action), Excel export (SheetJS), print/PDF CSS.
- **AI does ~5%**: writes exactly 2 `##` sections in order — **Executive Summary**, **Risk Assessment**. Prompt rules (`bpm-ai-prompt.txt`): copy verdicts verbatim, no arithmetic, only discuss labels present in the JSON, all-GOOD pages must not be called problematic, null-score (SPA) labels are not failures.
- **Providers**: 7 built-ins in shared `ai-reporter.properties` + unlimited custom. OpenAI-compatible chat/completions via `SharedHttpClient` (process-wide HTTP/2 singleton).
- **Label truncation**: when `labelResults.size() > MAX_AI_LABELS (20)`, sort by score ascending (worst first; null-score labels last via `Integer.MAX_VALUE`), send top 20, attach `remainingLabels` summary. GUI shows a confirm dialog, CLI logs a warning, HTML report shows a yellow notice bar. `BpmPromptContent` carries `totalLabels` / `includedLabels`; `RenderConfig.wasLabelTruncated()` propagates to the renderer.
- **HTML panel order (sidebar)**:
    1. Executive Summary (AI) — stakeholder overview.
    2. Performance Metrics (Java) — table with pagination, sorting, search.
    3. Performance Trends (Java) — 6 charts with SLA lines + per-label filter.
    4. SLA Compliance (Java) — Pass/Warning/Fail matrix + search.
    5. Critical Findings (Java) — bottleneck summary + per-transaction root cause / impact / action.
    6. Risk Assessment (AI) — headroom, boundary, SPA blind spots, trend risks.

### CLI (`bpm-ai-report`)

Two-step workflow:
```
jmeter -n -t test.jmx -Jbpm.output=results.jsonl   # 1. run test
bpm-ai-report -i results.jsonl --provider groq      # 2. render HTML
```

- Flags: `-i`, `-o`, `--provider`, `--config`, `--search`, `--regex`, `--exclude`, `--chart-interval`, `--scenario-name`, `--virtual-users`.
- Pipeline: parse JSONL → apply label filter → load properties → resolve + validate + ping provider → build prompt → call AI → render HTML → save.
- Exit codes: `0` success · `1` bad args · `2` parse error · `3` AI error · `4` write error · `5` unexpected.

### Resource files

- `bpm-ai-prompt.txt` — AI system prompt (5 rules, 2 sections, 6 edge cases).
- `bpm-default.properties` — `metrics.*` toggles, `network.topN`, SLA thresholds (`sla.fcp/lcp/cls/ttfb/jserrors/score.good|poor`), `bottleneck.*` ratios, `security.sanitize`, `bpm.output`, `bpm.debug`.
- `messages.properties` — JMeter resource bundle (`bpm_listener_gui=Browser Performance Metrics`).
- `META-INF/jmeter-plugins.properties` — Plugins Manager integration (id `bpm-jmeter-plugin-ai`, depends on `jpgc-webdriver`).

## Enforced invariants (do not violate)

- **`BpmConstants.TEST_ELEMENT_*` keys are frozen** — stored in `.jmx` files; renames break saved test plans (`OUTPUT_PATH`, `START_OFFSET`, `END_OFFSET`, `TRANSACTION_NAMES`, `REGEX`, `INCLUDE`, `CHART_INTERVAL`, `COLUMN_VISIBILITY`, `ID`).
- **JSONL schema is public** — `BpmResult` / `DerivedMetrics` `@JsonProperty` names are consumed by the CLI and external tools. Field renames are breaking changes.
- **`BpmConstants` is the single source of truth** — never hardcode column indices, column labels, property keys, or tooltip strings outside it.
- **Column model = 18 columns** (10 always-visible + 8 toggleable) in `COL_IDX_*` order. Always-visible (0–9): Label, Samples, Score, Render Time, Server Ratio, Frontend Time, FCP-LCP Gap, Stability, Headroom, Improvement Area. Toggleable (10–17): FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns.
- **`TotalPinnedRowSorter` pins the TOTAL row as the last view row for all sort directions.**
- **`BpmResult.performanceScore` is `Integer` (nullable)** — unboxing `null` to `int` NPEs inside Jackson and silently aborts JSONL writes.
- **Selenium types stay in `ChromeCdpCommandExecutor`** — the rest of the code talks to `CdpCommandExecutor`. Selenium presence is probed lazily via `Class.forName`.
- **Pure observer**: the listener never crashes the test — all exceptions caught, error state machine degrades gracefully, parent sampler results are never mutated.
- **UI contracts preserved**: `AbstractListenerGui` + `Clearable`.
- **Chrome-only via CDP** — acknowledged constraint; documented, not hidden.
- **Exit codes 0–5 are public contract** — consumed by CI; renumbering breaks pipelines.
- **AI prompt contract**: exactly 2 `##` sections (Executive Summary, Risk Assessment) in that order. `R1 COPY EXACTLY` (verdicts + `improvementArea` + all `trendAnalysis` fields) is non-negotiable — AI never reclassifies.
- **CommonMark is shaded to `io.github.sagaraggarwal86.shaded.commonmark`** — do not import `org.commonmark` in plugin code; the relocated package is what ships.

## Self-Maintenance

- **Ownership split**: `CLAUDE.md` = rules + architecture + invariants for Claude. `README.md` = user-facing features, install, config, CLI, troubleshooting. Change each in its own lane — do not duplicate content.
- **Auto-update CLAUDE.md**: after any session that changes design, architecture, invariants, or class responsibilities, review and patch this file in the same session.
  - Remove stale entries, dedupe, confirm every line still carries actionable information.
  - After the update, perform **one** re-review pass (not recursive) and verify:
    - **100% accuracy** — every claim matches current code.
    - **100% optimization** — no line can be tightened further.
    - **0% redundancy** — no fact stated more than once.
- **Auto-update README.md**: after feature changes, keep README current (feature list, config, CLI flags, exit codes, troubleshooting). Apply the README rules below.
- **Auto-compact**: suggest `/compact` before context becomes unwieldy.

### README update rules

1. **User-benefit framing** — describe features by what they do for the user, not by internal mechanics. Class names, package names, private fields stay in CLAUDE.md.
2. **Features table = summary only** — one short line per feature. Property keys, defaults, CLI flags, column names belong in their dedicated sections.
3. **Cross-platform shell blocks** — any command involving paths or env vars must show Linux/macOS, Windows PowerShell, and Windows cmd.
4. **Canonical reference per concept** — state each fact once (SLA thresholds, CLI flag table, exit codes, provider list). Cross-reference from other sections instead of repeating.
5. **Explicit conditionality** — for any conditional UI or report element (truncation banner, SPA `null` rendering, AI fallback), say *when* it appears.
6. **Self-updating references over hardcoded strings** — prefer badges and wildcards to literal version numbers where possible.
7. **Link CLAUDE.md, do not duplicate** — architecture, dependency graph, design decisions, and invariants live only in CLAUDE.md.
8. **Required sections** — Badges, Contents, Features, Requirements, Installation, Quick Start, feature sections, AI report usage, Known Limitations, Troubleshooting, Uninstall, Contributing, License.
9. **Exact versions, no `+`** — Java 17, JMeter 5.6.3 (not 17+, 5.6.3+) — matches the JMeter-5.6.3-exclusive invariant.
10. **Post-edit review** — verify **100% accuracy**, **100% optimization**, **0% redundancy**.
