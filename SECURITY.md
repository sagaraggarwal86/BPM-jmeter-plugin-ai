# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | Yes       |
| < 1.0   | No        |

## Threat Surface

BPM is a **local-first** JMeter listener. It opens no inbound sockets and emits no telemetry. The only outbound traffic is to the AI provider you configure in `ai-reporter.properties`.

| Area                  | Design                                                                                                                                                                   |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Chrome CDP link**   | Local WebSocket into the Chrome instance spawned by WebDriver Sampler. BPM only reads events.                                                                            |
| **Browser injection** | Observer snippets (`window.__bpm_*`) are injected via CDP `Page.addScriptToEvaluateOnNewDocument` and run in the test page's JS context.                                 |
| **Console redaction** | `security.sanitize=true` (default) replaces `Bearer` tokens, JWTs, and `password=`/`token=`/`secret=` values with `[REDACTED]` before reaching JSONL, the GUI, or the AI prompt. |
| **JSONL output**      | Metrics, transaction labels, and resource URLs only — no request/response bodies, cookies, or headers.                                                                   |
| **Prompt payload**    | Aggregated per-label verdicts, trends, improvement areas, labels, and top-N resource URLs. Raw samples are never sent.                                                   |
| **AI API calls**      | HTTPS for all built-in providers. Custom providers use whatever scheme is configured — the plugin does not enforce TLS.                                                  |
| **Markdown**          | CommonMark with default `escapeHtml=false` — AI-returned HTML passes through unescaped.                                                                                  |
| **HTML report**       | Static file on local disk. Loads Chart.js 4.4.1 and xlsx.mini 0.18.5 from cdnjs.                                                                                         |
| **API keys**          | Read from `ai-reporter.properties`. Never logged or embedded in reports; sent only as `Authorization: Bearer` to the configured provider.                                |

## What BPM Does NOT Protect Against

- **AI provider trust**: labels and resource URLs are sent to the third-party endpoint you configure. Scrub sensitive identifiers in the test plan before running.
- **Compromised provider**: HTML pass-through lets a compromised provider inject `<script>` into the report. Treat reports from untrusted providers as untrusted HTML.
- **Compromised test target**: the observer runs in the test page's JS context; a hostile site can tamper with `window.__bpm_*` buffers to forge metrics.
- **API key leakage**: keys sit in plaintext at `$JMETER_HOME/bin/ai-reporter.properties`. Protect with OS permissions; never commit; rotate if shared.
- **CDN compromise**: air-gapped setups should host Chart.js and xlsx.mini locally and rewrite the URLs.
- **Disabled sanitisation**: `security.sanitize=false` lets raw console messages (potentially containing tokens) reach JSONL, the GUI, and the AI prompt.

## Reporting a Vulnerability

1. **Do not** open a public GitHub issue.
2. Use GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability) with a description, reproduction steps, and impact.

## Bundled Dependencies

| Dependency                | Version | Purpose                  |
|---------------------------|---------|--------------------------|
| CommonMark                | 0.28.0  | Markdown → HTML          |
| commonmark-ext-gfm-tables | 0.24.0  | GFM tables in AI reports |

Both are shaded to `io.github.sagaraggarwal86.shaded.commonmark` to isolate from JMeter's classpath. All other runtime dependencies (`ApacheJMeter_core`, `selenium-chrome-driver`, `jackson-databind`) are `provided` scope.
