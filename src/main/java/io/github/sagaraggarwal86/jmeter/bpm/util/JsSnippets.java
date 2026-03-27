package io.github.sagaraggarwal86.jmeter.bpm.util;

/**
 * Compile-time JavaScript string constants used by BPM for CDP-based metric collection.
 *
 * <p>All constants in this class are JavaScript source strings passed to
 * {@code CdpCommandExecutor.executeScript()} or used as CDP method names in
 * {@code CdpCommandExecutor.executeCdpCommand()}. Keeping all JavaScript in one place makes
 * cross-platform testing and future maintenance straightforward.
 *
 * <p>This class is not instantiable; all members are static.
 *
 * <h2>Injection lifecycle</h2>
 * <ol>
 *   <li>{@link #INJECT_OBSERVERS} is executed once when the CDP session is opened to register
 *       persistent {@code PerformanceObserver} callbacks for LCP and CLS.</li>
 *   <li>{@link #COLLECT_WEB_VITALS} is executed once per sampler to read the accumulated values
 *       written by those observers, together with FCP and TTFB from Navigation Timing.</li>
 *   <li>{@link #CDP_METHOD_PERFORMANCE_GET_METRICS} is the CDP domain method name passed to
 *       {@code executeCdpCommand} by {@code RuntimeCollector} to retrieve Chrome runtime metrics
 *       (heap, DOM nodes, layout count, style-recalc count).</li>
 *   <li>{@link #COLLECT_RESOURCE_TIMING} is a supplementary script that drains the browser's
 *       Resource Timing buffer and clears it, used as a fallback or for cross-validation when
 *       the CDP Network domain is unavailable.</li>
 * </ol>
 */
public final class JsSnippets {

    private JsSnippets() {
        throw new UnsupportedOperationException("JsSnippets is a utility class");
    }

    // ── Observer injection ────────────────────────────────────────────────────────────────────

    /**
     * JavaScript executed once at CDP session initialisation. Registers two
     * {@code PerformanceObserver} instances that accumulate LCP and CLS values into
     * {@code window.__bpm_lcp} and {@code window.__bpm_cls} respectively.
     *
     * <p>Both observers use {@code buffered: true} so entries already recorded before injection
     * are immediately delivered to the callback. This is safe to call on page that has already
     * started rendering.
     *
     * <p>The script is idempotent: re-injecting after a CDP session re-init overwrites the
     * previous window state but does not stack duplicate observers because the session re-init
     * happens after a navigation or browser restart.
     */
    public static final String INJECT_OBSERVERS = """
            (function() {
              // LCP: store the startTime of the last (most recent) largest-contentful-paint entry.
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                if (entries.length > 0) {
                  window.__bpm_lcp = entries[entries.length - 1].startTime;
                }
              }).observe({type: 'largest-contentful-paint', buffered: true});

              // CLS: accumulate layout-shift values, ignoring shifts caused by user input.
              window.__bpm_cls = window.__bpm_cls || 0;
              new PerformanceObserver(function(list) {
                var entries = list.getEntries();
                for (var i = 0; i < entries.length; i++) {
                  if (!entries[i].hadRecentInput) {
                    window.__bpm_cls = (window.__bpm_cls || 0) + entries[i].value;
                  }
                }
              }).observe({type: 'layout-shift', buffered: true});
            })();
            """;

    // ── Web Vitals collection ─────────────────────────────────────────────────────────────────

    /**
     * JavaScript executed once per sampler to read all four Web Vitals in a single round-trip.
     * Returns a plain JavaScript object that Selenium converts to a {@code Map<String, Object>}
     * with the following keys and value types:
     *
     * <table border="1">
     *   <caption>Returned map keys</caption>
     *   <tr><th>Key</th><th>Java type after Selenium conversion</th><th>Units</th></tr>
     *   <tr><td>{@code lcp}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     *   <tr><td>{@code cls}</td><td>{@code Number} (double)</td><td>unitless score</td></tr>
     *   <tr><td>{@code fcp}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     *   <tr><td>{@code ttfb}</td><td>{@code Number} (double)</td><td>milliseconds</td></tr>
     * </table>
     *
     * <p>All values default to {@code 0} when the underlying API entry is absent, ensuring
     * the caller never receives {@code null} values for numeric fields.
     *
     * <p>FCP is sourced from the {@code first-contentful-paint} Paint Timing entry.
     * TTFB is sourced from the Navigation Timing L2 API ({@code responseStart}).
     * LCP and CLS are sourced from the window globals set by {@link #INJECT_OBSERVERS}.
     */
    public static final String COLLECT_WEB_VITALS = """
            (function() {
              var nav = (performance.getEntriesByType('navigation') || [])[0] || {};
              var fcpEntries = performance.getEntriesByName('first-contentful-paint') || [];
              var fcpEntry = fcpEntries.length > 0 ? fcpEntries[0] : null;
              return {
                lcp:  window.__bpm_lcp || 0,
                cls:  window.__bpm_cls || 0,
                fcp:  fcpEntry ? fcpEntry.startTime : 0,
                ttfb: nav.responseStart || 0
              };
            })();
            """;

    // ── CDP command names ─────────────────────────────────────────────────────────────────────

    /**
     * CDP domain method name passed to {@code CdpCommandExecutor.executeCdpCommand()} by
     * {@code RuntimeCollector} to retrieve Chrome runtime performance metrics.
     *
     * <p>The CDP response contains a {@code metrics} array of {@code {name, value}} objects.
     * Keys of interest: {@code JSHeapUsedSize}, {@code Nodes}, {@code LayoutCount},
     * {@code RecalcStyleCount}.
     *
     * @see <a href="https://chromedevtools.github.io/devtools-protocol/tot/Performance/#method-getMetrics">
     *      CDP Performance.getMetrics</a>
     */
    public static final String CDP_METHOD_PERFORMANCE_GET_METRICS = "Performance.getMetrics";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate the
     * Performance domain before calling {@link #CDP_METHOD_PERFORMANCE_GET_METRICS}.
     */
    public static final String CDP_DOMAIN_PERFORMANCE = "Performance";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Network
     * event capture ({@code Network.responseReceived}, {@code Network.loadingFailed}, etc.).
     */
    public static final String CDP_DOMAIN_NETWORK = "Network";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Page-level
     * events (navigations, load events) used for session lifecycle management.
     */
    public static final String CDP_DOMAIN_PAGE = "Page";

    /**
     * CDP domain name passed to {@code CdpCommandExecutor.enableDomain()} to activate Log
     * domain events, which surface browser console messages as CDP events.
     */
    public static final String CDP_DOMAIN_LOG = "Log";

    // ── Resource Timing (supplementary) ──────────────────────────────────────────────────────

    /**
     * JavaScript that reads all {@code resource} performance entries accumulated since the last
     * call (or page load), then clears the browser's performance buffer to avoid double-counting
     * on the next sample.
     *
     * <p>Returns a JSON string — not a JS object — because the resource entry array may be large
     * and is safer to transmit as a pre-serialised string that the collector parses via Jackson.
     * Each element in the {@code resources} array is an object with:
     * <ul>
     *   <li>{@code url} — the resource URL (trimmed to 512 characters to limit payload size)</li>
     *   <li>{@code duration} — total round-trip time in milliseconds</li>
     *   <li>{@code transferSize} — bytes transferred (0 for cached resources)</li>
     *   <li>{@code ttfb} — time to first byte ({@code responseStart}) in milliseconds</li>
     *   <li>{@code status} — HTTP status code if available, otherwise 0</li>
     * </ul>
     *
     * <p>This script is used as a fallback when CDP Network domain events are unavailable and
     * for cross-validation in end-to-end tests.
     */
    public static final String COLLECT_RESOURCE_TIMING = """
            (function() {
              var entries = performance.getEntriesByType('resource') || [];
              var resources = [];
              for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                resources.push({
                  url:          (e.name || '').substring(0, 512),
                  duration:     e.duration || 0,
                  transferSize: e.transferSize || 0,
                  ttfb:         e.responseStart || 0,
                  status:       e.responseStatus || 0
                });
              }
              performance.clearResourceTimings();
              return JSON.stringify({resources: resources});
            })();
            """;
}
