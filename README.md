# BPM — Browser Performance Metrics

[![Release](https://img.shields.io/github/v/release/sagaraggarwal86/bpm-jmeter-plugin-ai?label=release&sort=semver&cacheSeconds=300)](https://github.com/sagaraggarwal86/bpm-jmeter-plugin-ai/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sagaraggarwal86/bpm-jmeter-plugin-ai.svg?label=Maven%20Central&cacheSeconds=300)](https://central.sonatype.com/artifact/io.github.sagaraggarwal86/bpm-jmeter-plugin-ai)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A live Apache JMeter listener plugin that captures browser-side rendering and performance metrics from WebDriver Sampler executions via Chrome DevTools Protocol (CDP). Produces Core Web Vitals, a network waterfall, runtime health, JS errors, a composite Performance Score, Improvement Area detection, JSONL output, and an AI-generated HTML performance report.

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [What BPM Captures](#what-bpm-captures)
- [Filter Settings](#filter-settings)
- [GUI Overview](#gui-overview)
- [AI Performance Report](#ai-performance-report)
- [Local LLM Support](#local-llm-support)
- [CLI Mode](#cli-mode)
- [Output Files](#output-files)
- [Configuration](#configuration)
- [Non-GUI / CI Usage](#non-gui--ci-usage)
- [Performance Impact](#performance-impact)
- [Multiple BPM Listener Instances](#multiple-bpm-listener-instances)
- [Known Limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Uninstall](#uninstall)
- [Contributing](#contributing)
- [License](#license)

---

## Features

| Feature                   | Description                                                                                               |
|---------------------------|-----------------------------------------------------------------------------------------------------------|
| **Live Metric Capture**   | FCP, LCP, CLS, TTFB, network waterfall, runtime health, JS errors — captured per WebDriver Sampler action |
| **Performance Score**     | Composite 0–100 score weighted against Google Core Web Vitals thresholds                                  |
| **Improvement Area**      | Automatic first-match detection of the primary bottleneck (server, network, rendering, DOM, reliability)  |
| **Live Results Table**    | 10 always-visible derived columns + 8 toggleable raw-metric columns with SLA-based cell colouring         |
| **Start / End Offset**    | Exclude ramp-up and ramp-down periods by entering a time window in seconds                                |
| **Transaction Filter**    | Filter by transaction name with Include/Exclude mode, plain text or regex                                 |
| **Column Visibility**     | Show or hide raw-metric columns via a dropdown multi-select                                               |
| **Test Time Info**        | Start, End, and Duration shown automatically during and after the test                                    |
| **SLA Thresholds**        | Configurable per-metric thresholds — breaching cells highlighted                                          |
| **CSV Export**            | Save visible columns to CSV                                                                               |
| **AI Performance Report** | Styled HTML report with deep-dive analysis via any OpenAI-compatible provider                             |
| **CLI Mode**              | Generate the AI report from the command line — no JMeter GUI required                                    |
| **JSONL Output**          | One JSON record per sampler action — CI-friendly, AI-ready                                                |
| **Pure Observer**         | Zero impact on test results — never modifies SampleResults, JTL output, or other listeners                |

---

## Requirements

| Requirement       | Version                                   |
|-------------------|-------------------------------------------|
| Java              | 17                                        |
| Apache JMeter     | 5.6.3                                     |
| WebDriver Sampler | jpgc-webdriver *(JMeter Plugins Manager)* |
| Chrome / Chromium | Any recent version                        |
| Maven             | 3.8+ *(build only)*                       |
| AI API key        | *(AI report feature only)*                |

> [!IMPORTANT]
> **BPM requires WebDriver Sampler (jpgc-webdriver) with Chrome/Chromium.** All metrics are captured via Chrome DevTools Protocol — Firefox, Safari, and Edge are not supported. HTTP Samplers and other non-WebDriver sampler types produce no BPM data.

---

## Installation

### From Releases (Recommended)

1. Download the latest JAR from [GitHub Releases](https://github.com/sagaraggarwal86/bpm-jmeter-plugin-ai/releases) or [Maven Central](https://central.sonatype.com/artifact/io.github.sagaraggarwal86/bpm-jmeter-plugin-ai).
2. Copy it to `<JMETER_HOME>/lib/ext/bpm-jmeter-plugin-ai-<version>.jar`.
3. Restart JMeter.
4. *(Optional — CLI mode)* Copy `src/main/scripts/bpm-ai-report.bat` (Windows) or `bpm-ai-report.sh` (macOS/Linux) to `<JMETER_HOME>/bin/`.
5. *(Optional — AI report)* Copy [docs/ai-reporter.properties](docs/ai-reporter.properties) to `<JMETER_HOME>/bin/` and set at least one `api.key`. See [API Key Setup](#api-key-setup).
6. *(Optional — SLA tuning)* Copy [docs/bpm.properties](docs/bpm.properties) to `<JMETER_HOME>/bin/` to customise SLA thresholds. BPM auto-generates one on first run if absent.

### Build from Source

```bash
git clone https://github.com/sagaraggarwal86/bpm-jmeter-plugin-ai.git
cd bpm-jmeter-plugin-ai
mvn clean verify
```

Then copy the built JAR into `<JMETER_HOME>/lib/ext/`:

- **Linux / macOS**: `cp target/bpm-jmeter-plugin-ai-*.jar "$JMETER_HOME/lib/ext/"`
- **Windows (PowerShell)**: `Copy-Item target\bpm-jmeter-plugin-ai-*.jar "$env:JMETER_HOME\lib\ext\"`
- **Windows (cmd)**: `copy target\bpm-jmeter-plugin-ai-*.jar "%JMETER_HOME%\lib\ext\"`

---

## Quick Start

1. Create a test plan with a **Thread Group** containing one or more **WebDriver Samplers** using Chrome.
2. **Add → Listener → Browser Performance Metrics**.
3. Run the test.
4. The live table populates with performance scores, render times, and improvement areas.

No configuration required. BPM automatically instruments every WebDriver Sampler in the Thread Group.

---

## What BPM Captures

### Raw metrics (4 tiers)

| Tier | Category   | Metrics                                                 | Overhead   |
|------|------------|---------------------------------------------------------|------------|
| 1    | Web Vitals | FCP, LCP, CLS, TTFB                                     | Negligible |
| 2    | Network    | Total requests, total bytes, top N slowest + all failed | Low        |
| 3    | Runtime    | JS heap, DOM nodes, layout count, style-recalc count    | Low        |
| 4    | Console    | JS error count, warning count, sanitised messages       | Negligible |

### Derived metrics

| Metric                        | Formula                     | Purpose                                    |
|-------------------------------|-----------------------------|--------------------------------------------|
| **Performance Score** (0–100) | Weighted composite          | Single-number health indicator             |
| **Render Time** (ms)          | LCP − TTFB                  | Pure client-side render time               |
| **Server Ratio** (%)          | (TTFB / LCP) × 100          | Server vs client split                     |
| **Frontend Time** (ms)        | FCP − TTFB                  | Browser parse + blocking-script time       |
| **FCP-LCP Gap** (ms)          | LCP − FCP                   | Lazy-load or render-blocking indicator     |
| **Stability**                 | CLS-based                   | Stable / Minor Shifts / Unstable           |
| **Headroom** (%)              | 100 − (LCP / lcpPoor × 100) | LCP budget remaining before Poor threshold |
| **Improvement Area**          | Categorical detection       | The primary thing to fix                   |

### Performance Score

Weighted average against Google Core Web Vitals thresholds:

- **Weights**: LCP 40%, FCP 15%, CLS 15%, TTFB 15%, JS Errors 15%.
- **Bands**: Good ≥ 90 (green) · Needs Work 50–89 (amber) · Poor < 50 (red).
- Returns `null` when available metric weight < 0.45 (e.g. SPA-stale actions with only CLS + errors).

### Improvement Area detection (first-match-wins)

1. **Fix Network Failures** — failed network requests detected.
2. **Reduce Server Response** — TTFB > 60% of LCP.
3. **Optimise Heavy Assets** — slowest resource > 40% of LCP.
4. **Reduce Render Work** — render time > 60% of LCP.
5. **Reduce DOM Complexity** — layout count > DOM nodes × 0.5.
6. **None** — all within acceptable range.

All thresholds are tunable in `bpm.properties` — see [Configuration](#configuration).

---

## Filter Settings

### Start / End Offset

Exclude ramp-up and ramp-down samples by entering a time window in seconds (measured from the first sample).

| Start Offset | End Offset | Behaviour                                       |
|--------------|------------|-------------------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                            |
| `5`          | *(empty)*  | Skip first 5 seconds; include the rest          |
| *(empty)*    | `25`       | Include up to 25 seconds; skip everything after |
| `5`          | `25`       | Include only the 5s–25s window                  |

### Transaction Names

Filter the results table by typing in the **Transaction Names** field.

| Mode                     | Behaviour                        | Example                          |
|--------------------------|----------------------------------|----------------------------------|
| **Plain text** (default) | Case-insensitive substring match | `login` matches `Login Flow`     |
| **RegEx** (checkbox on)  | Java regex pattern match         | `Login\|Checkout` matches either |

| Filter Mode           | Behaviour                                   |
|-----------------------|---------------------------------------------|
| **Include** (default) | Show only transactions matching the pattern |
| **Exclude**           | Hide transactions matching the pattern      |

Filters are manual — click **Apply Filters** to commit changes. Editing a field alone does not re-filter.

---

## GUI Overview

- **Output File** — JSONL output path with Browse. Press Enter on an existing file to load it.
- **Filter Settings** — Start/End Offset, Transaction Names, Column Selector, Apply Filters.
- **Test Time Info** — Start, End, Duration (live).
- **Overall Performance Score** — coloured progress bar with Good / Needs Work / Poor counts.
- **Results Table** — 10 always-visible derived columns + 8 toggleable raw columns; SLA-coloured cells; TOTAL row pinned to the last view row for all sort directions.
- **AI Report** — provider dropdown, **Reload List** (re-reads `ai-reporter.properties` without restarting JMeter), and **Generate AI Report** button.
- **Save Table Data** — CSV export of visible columns.

Click **Select Columns** to toggle raw metric columns (FCP, LCP, CLS, TTFB, Reqs, Size, Errs, Warns). All OFF by default — the 10 derived columns tell the full story.

---

## AI Performance Report

Click **Generate AI Report** to analyse the captured BPM data with any supported AI provider. A save dialog lets you choose where to save the self-contained HTML report; it opens automatically in your browser.

**Supported providers:** Groq (free), Gemini (free), Mistral (free), DeepSeek (free), Cerebras (free), OpenAI (paid), Claude (paid), Ollama (local/free) — or any OpenAI-compatible endpoint.

### Pre-computed analysis

The plugin pre-computes all analytical results in Java before sending data to the AI provider. This ensures deterministic, accurate outputs regardless of which AI model is used:

| Pre-computed field | Description                                                |
|--------------------|------------------------------------------------------------|
| SLA verdicts       | GOOD / NEEDS_WORK / POOR per metric per transaction        |
| Trend analysis     | RISING / FALLING / STABLE direction with percentage change |
| Alert sentences    | Pre-written prose the AI copies verbatim                   |
| Improvement areas  | Per-transaction categorical detection                      |
| Critical Findings  | Root cause diagnosis + recommended actions                 |

The AI provider's role (~5% of the work) is to generate 2 sections of narrative prose (Executive Summary, Risk Assessment). Java generates the other 4 panels (Performance Metrics, Performance Trends, SLA Compliance, Critical Findings).

### Report panels

| # | Panel               | Source | Description                                                             |
|---|---------------------|--------|-------------------------------------------------------------------------|
| 1 | Executive Summary   | AI     | Non-technical overview with key findings and trend summary              |
| 2 | Performance Metrics | Java   | Full data table with pagination, column sorting, and transaction search |
| 3 | Performance Trends  | Java   | 6 Chart.js charts (Score, LCP, FCP, TTFB, CLS, Render) with SLA lines   |
| 4 | SLA Compliance      | Java   | Pass/Warning/Fail verdict matrix per metric per transaction             |
| 5 | Critical Findings   | Java   | Only transactions needing attention, with root cause and actions        |
| 6 | Risk Assessment     | AI     | Headroom, boundary, SPA blind spots, and trend risks                    |

### Report features

- Sidebar panel navigation and metadata grid (scenario name, virtual users, run date/time, duration).
- Page-based pagination (10/25/50/100 rows).
- Click-to-sort on all table columns.
- Transaction search on Performance Metrics and SLA Compliance.
- Per-transaction chart filter on Performance Trends.
- SLA threshold lines on charts (green for Score, red for LCP/FCP/TTFB/CLS).
- Excel export via SheetJS (all transactions, not paginated).
- Print/PDF CSS for offline sharing.
- Single HTML file — no external assets except Chart.js and SheetJS via CDN.

### API key setup

Place `ai-reporter.properties` in `<JMETER_HOME>/bin/` and set at least one `api.key`:

```properties
ai.reporter.groq.api.key=gsk_your-key-here
ai.reporter.gemini.api.key=AIza-your-key-here
ai.reporter.mistral.api.key=your-key-here
ai.reporter.cerebras.api.key=csk-your-key-here
ai.reporter.deepseek.api.key=your-key-here
ai.reporter.openai.api.key=sk-your-key-here
ai.reporter.claude.api.key=sk-ant-your-key-here
```

Select the provider from the dropdown next to **Generate AI Report**. A full sample with all fields is at [docs/ai-reporter.properties](docs/ai-reporter.properties).

### Provider order

By default, providers appear in the dropdown in built-in order (Groq, Gemini, Mistral, DeepSeek, Cerebras, OpenAI, Claude). Override with `ai.reporter.order`:

```properties
ai.reporter.order=cerebras,mistral,groq
```

Only providers with a non-blank `api.key` are shown. Providers not listed in `ai.reporter.order` appear after the listed ones, alphabetically.

### Label truncation

When a test has more than 20 unique transaction labels, the AI prompt is limited to the 20 worst-performing labels (sorted by score ascending — null-score labels last). A summary of the omitted labels is included in the prompt so the AI can acknowledge the scope, and the HTML report shows a yellow notice bar. Verdicts, metrics, and trend analysis remain accurate for all transactions in the Java-generated panels — truncation only affects the AI narrative sections.

### Custom providers

Any OpenAI-compatible endpoint can be added:

```properties
ai.reporter.myprovider.api.key=your-key-here
ai.reporter.myprovider.base.url=https://api.myprovider.com/v1
ai.reporter.myprovider.model=my-model-name
ai.reporter.myprovider.tier=Free
ai.reporter.myprovider.timeout.seconds=90
ai.reporter.myprovider.max.tokens=8192
ai.reporter.myprovider.temperature=0.3
```

The `tier` sets the dropdown label (e.g. `My Provider (Free)`).

---

## Local LLM Support

The plugin supports **Ollama** as a fully offline, free alternative to cloud providers. No API key, no internet needed after the model is pulled.

1. Install Ollama from [https://ollama.com](https://ollama.com).
2. Pull a model:
   ```bash
   ollama pull qwen2.5:7b     # ~5 GB — recommended
   ollama pull mistral        # ~4 GB — strong reasoning
   ollama pull llama3.2       # ~2 GB — fast
   ```
3. Add to `ai-reporter.properties`:
   ```properties
   ai.reporter.ollama.api.key=ollama
   ai.reporter.ollama.model=qwen2.5:7b
   ai.reporter.ollama.base.url=http://localhost:11434/v1
   ai.reporter.ollama.timeout.seconds=180
   ai.reporter.ollama.max.tokens=8192
   ai.reporter.ollama.temperature=0.3
   ```
4. Select **ollama** from the provider dropdown and click **Generate AI Report**.

Ensure at least 8 GB RAM free before pulling a 7B model. On CPU-only machines keep `timeout.seconds ≥ 180` — generation can take 1–3 minutes.

---

## CLI Mode

Generate an AI performance report from the command line — no JMeter GUI required.

Install the wrapper script per [Installation step 4](#from-releases-recommended), then run in two steps:

```bash
# Step 1: run the JMeter test
jmeter -n -t test.jmx -l results.jtl -Jbpm.output=bpm-results.jsonl

# Step 2: generate the AI report
bpm-ai-report -i bpm-results.jsonl --provider groq
```

### All options

```
Required:
  -i, --input FILE            BPM JSONL results file

Output:
  -o, --output FILE           HTML report path (default: bpm-ai-report.html)

AI Provider:
  --provider NAME             Provider name (groq, openai, claude, etc.)
                              Default: first configured in properties file
  --config FILE               Path to ai-reporter.properties
                              Default: $JMETER_HOME/bin/ai-reporter.properties

Filter Options:
  --chart-interval INT        Seconds per chart bucket, 0=auto (default: 0)
  --search STRING             Label filter text (include mode by default)
  --regex                     Treat --search as regex
  --exclude                   Exclude matching labels (default: include)

Report Metadata:
  --scenario-name STRING      Scenario name for report header
  --description STRING        Scenario description
  --virtual-users INT         Virtual user count for report header

Help:
  -h, --help                  Show help message
```

### Exit codes

| Code | Meaning                                                 |
|------|---------------------------------------------------------|
| `0`  | Success — report generated                              |
| `1`  | Invalid command-line arguments                          |
| `2`  | JSONL parse error                                       |
| `3`  | AI provider error (invalid key, ping failed, API error) |
| `4`  | Report file write error                                 |
| `5`  | Unexpected error                                        |

> AI errors are **not** silently swallowed — the CLI exits `3` with the provider message rather than producing a data-only report. Retry, switch provider, or fix `ai-reporter.properties`.

---

## Output Files

### JSONL (primary)

One JSON object per line, per WebDriver Sampler execution. Default path: `bpm-results.jsonl` (resolution order: `-Jbpm.output` > GUI TestElement property > `bpm.properties` > default).

Each record contains: `bpmVersion`, `timestamp`, `threadName`, `iterationNumber`, `samplerLabel`, `samplerSuccess`, `samplerDuration`, raw metric objects (`webVitals`, `network`, `runtime`, `console`), and a `derived` object with score, improvement area, ratios, stability, and headroom.

The writer flushes after every record, so the file is safe to tail during a long run.

---

## Configuration

### bpm.properties

Auto-generated on first run at `<JMETER_HOME>/bin/bpm.properties`. All defaults match Google Core Web Vitals thresholds.

| Section          | Key                                       | Default         | Description                               |
|------------------|-------------------------------------------|-----------------|-------------------------------------------|
| Metric toggles   | `metrics.webvitals`                       | `true`          | Enable/disable Web Vitals collection      |
|                  | `metrics.network`                         | `true`          | Enable/disable Network collection         |
|                  | `metrics.runtime`                         | `true`          | Enable/disable Runtime collection         |
|                  | `metrics.console`                         | `true`          | Enable/disable Console collection         |
| Network          | `network.topN`                            | `5`             | Slowest resources to capture              |
| SLA: FCP         | `sla.fcp.good` / `sla.fcp.poor`           | `1800` / `3000` | FCP thresholds (ms)                       |
| SLA: LCP         | `sla.lcp.good` / `sla.lcp.poor`           | `2500` / `4000` | LCP thresholds (ms)                       |
| SLA: CLS         | `sla.cls.good` / `sla.cls.poor`           | `0.1` / `0.25`  | CLS thresholds                            |
| SLA: TTFB        | `sla.ttfb.good` / `sla.ttfb.poor`         | `800` / `1800`  | TTFB thresholds (ms)                      |
| SLA: Errors      | `sla.jserrors.good` / `sla.jserrors.poor` | `0` / `5`       | JS error count thresholds                 |
| SLA: Score       | `sla.score.good` / `sla.score.poor`       | `90` / `50`     | Performance score thresholds              |
| Improvement Area | `bottleneck.server.ratio`                 | `60`            | Server: TTFB % of LCP                     |
|                  | `bottleneck.resource.ratio`               | `40`            | Resource: slowest % of LCP                |
|                  | `bottleneck.client.ratio`                 | `60`            | Client: render time % of LCP              |
|                  | `bottleneck.layoutThrash.factor`          | `0.5`           | Layout: layoutCount vs domNodes           |
| Security         | `security.sanitize`                       | `true`          | Redact sensitive data in console messages |
| Debug            | `bpm.debug`                               | `false`         | Detailed operational logging              |

### Version upgrades

When BPM detects a version mismatch in `bpm.properties`, it backs up the old file as `bpm.properties.v<old>.bak` and writes the new template.

---

## Non-GUI / CI Usage

BPM works fully in non-GUI mode. The live table is absent; JSONL output and log summary are produced normally.

```bash
# Standard run
jmeter -n -t test.jmx -l results.jtl -Jbpm.output=bpm-results.jsonl

# CI run with build ID + debug + AI report
jmeter -n -t test.jmx -l results.jtl \
  -Jbpm.output=build-${BUILD_ID}-bpm.jsonl -Jbpm.debug=true

./bpm-ai-report.sh -i build-${BUILD_ID}-bpm.jsonl --provider mistral \
  --scenario-name "Build ${BUILD_ID}" --virtual-users 50 -o report.html
```

Archive the JSONL file and HTML report as build artifacts. Only `bpm.output` and `bpm.debug` accept `-J` overrides; all other properties are read from `bpm.properties`.

When `-Jbpm.output` is set in non-GUI mode, BPM auto-enables the first disabled `BpmListener` in the plan — no need to re-save the `.jmx` with the listener enabled.

---

## Performance Impact

BPM is designed as a pure observer with minimal overhead:

| Metric                           | Impact                                             |
|----------------------------------|----------------------------------------------------|
| Inter-sampler delay              | ~10–25 ms per sample (CDP round-trips)             |
| Transaction Controller inflation | ~1–2%                                              |
| Throughput reduction             | ~1% (negligible for WebDriver tests)               |
| Memory                           | Running averages per label — not stored per-sample |
| JSONL writes                     | Flushed after every record                         |

---

## Multiple BPM Listener Instances

A test plan may contain multiple `BpmListener` elements. Each instance operates independently:

- **Per-element file check** — on test start, a single pre-flight dialog lists every conflicting output file across all enabled listeners and offers **Overwrite** or **Don't Start JMeter Engine**.
- **Output-path dedup** — if two listeners resolve to the same output path, the first wins; the second skips JSONL setup to prevent corruption.
- **Independent GUI state** — each listener maintains its own writer, CDP sessions, health counters, and rawResults.
- **Default output path** — when no path is configured, all listeners fall back to `bpm-results.jsonl`. Always assign distinct paths when using multiple listeners.

---

## Known Limitations

- **Chrome-only via CDP** — non-Chrome browsers are detected and skipped with a warning; non-WebDriver sampler types are silently skipped.
- **SPA navigation** — client-side route changes fire no new `largest-contentful-paint` event, so LCP/FCP/TTFB are reported as `null` for those samples (see [Performance Score](#performance-score)).
- **Charts require internet** — Chart.js and SheetJS load from CDN; an offline browser shows blank charts and disables Excel export.
- **Provider token limits** — very large test runs can exceed a provider's `max_tokens`; the report shows a truncation banner when this happens.

---

## Troubleshooting

**The plugin does not appear in JMeter's Add → Listener menu.**
Verify the JAR is in `<JMETER_HOME>/lib/ext/`. Restart JMeter after copying.

**The Generate AI Report button is greyed out.**
No configured provider found, or no data in the table. Verify `ai-reporter.properties` exists in `<JMETER_HOME>/bin/` with at least one `api.key` set, and that data is loaded.

**"No performance data available" dialog appears.**
No test data captured or loaded. Run a test or load a JSONL file first.

**Charts are blank / Excel export does nothing.**
Open the HTML file in a browser with internet access — Chart.js and SheetJS load from CDN.

**The AI report is missing sections or shows a truncation banner.**
The provider hit its `max_tokens` limit. Increase it in `ai-reporter.properties`:

```properties
ai.reporter.<provider>.max.tokens=16000
```

**API key rejected — "HTTP 401" error.**
The `api.key` value is incorrect or revoked. Update `ai-reporter.properties`.

**Rate limit exceeded — "HTTP 429" error.**
Wait and retry, or switch to a different provider.

**Ollama: "Could not connect" error.**
Start Ollama with `ollama serve` and verify it is reachable at `http://localhost:11434`.

**The AI report times out.**
Increase `timeout.seconds` for the provider. Default is 60 seconds; for local models, 120–300 seconds is recommended.

**SPA actions show null scores.**
Expected for client-side route changes — see [Known Limitations](#known-limitations).

**`bpm.properties` was overwritten after upgrade.**
Expected on version bumps — the prior file is saved as `bpm.properties.v<old>.bak`. See [Version upgrades](#version-upgrades).

---

## Uninstall

Remove the JAR from `<JMETER_HOME>/lib/ext/`. If you installed the CLI scripts or properties files, remove them from `<JMETER_HOME>/bin/` as well. Generated JSONL, CSV, and HTML files are standalone — delete them manually if no longer needed.

---

## Contributing

Bug reports and pull requests are welcome via [GitHub Issues](https://github.com/sagaraggarwal86/bpm-jmeter-plugin-ai/issues).

Before submitting a pull request:

```bash
mvn clean verify          # All tests must pass (JaCoCo ≥ 84% line coverage enforced)
```

- Test manually with JMeter 5.6.3 on your platform.
- Keep each pull request focused on a single change.
- Security issues: follow [SECURITY.md](SECURITY.md).
- See [CLAUDE.md](CLAUDE.md) for architecture, design decisions, and enforced invariants.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
