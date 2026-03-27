package io.github.sagaraggarwal86.jmeter.bpm.collectors;

import io.github.sagaraggarwal86.jmeter.bpm.core.CdpCommandExecutor;
import io.github.sagaraggarwal86.jmeter.bpm.core.MetricsBuffer;
import io.github.sagaraggarwal86.jmeter.bpm.model.WebVitalsResult;
import io.github.sagaraggarwal86.jmeter.bpm.util.JsSnippets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects Core Web Vitals (FCP, LCP, CLS, TTFB) from the browser.
 *
 * <p>Uses {@link JsSnippets#COLLECT_WEB_VITALS} which reads:</p>
 * <ul>
 *   <li>{@code window.__bpm_lcp} — set by the injected PerformanceObserver</li>
 *   <li>{@code window.__bpm_cls} — accumulated layout shift score</li>
 *   <li>FCP from Paint Timing API ({@code first-contentful-paint} entry)</li>
 *   <li>TTFB from Navigation Timing L2 ({@code responseStart})</li>
 * </ul>
 *
 * <p><strong>SPA stale detection:</strong> For single-page application navigation,
 * the old LCP value lingers because no new {@code largest-contentful-paint} event
 * fires for client-side route changes. This collector tracks the previous LCP
 * value per thread and returns {@code null} if LCP is unchanged, indicating
 * no new LCP event occurred for this action.</p>
 *
 * <p>Thread safety: previous LCP tracking uses {@link ConcurrentHashMap}
 * keyed by thread name.</p>
 */
public final class WebVitalsCollector implements MetricsCollector<WebVitalsResult> {

    private static final Logger log = LoggerFactory.getLogger(WebVitalsCollector.class);

    /**
     * Tracks the last seen LCP value per thread for SPA stale detection.
     * Key: thread name, Value: previous LCP in milliseconds.
     */
    private final ConcurrentHashMap<String, Double> previousLcpByThread = new ConcurrentHashMap<>();

    /**
     * Collects Web Vitals metrics from the browser.
     *
     * @param executor the CDP command executor
     * @param buffer   the metrics buffer (not used by this collector)
     * @return the Web Vitals result, or {@code null} if LCP is stale (SPA navigation
     *         with no new largest-contentful-paint event)
     */
    @Override
    @SuppressWarnings("unchecked")
    public WebVitalsResult collect(CdpCommandExecutor executor, MetricsBuffer buffer) {
        Object result = executor.executeScript(JsSnippets.COLLECT_WEB_VITALS);

        if (!(result instanceof Map<?, ?> rawMap)) {
            log.debug("BPM: COLLECT_WEB_VITALS returned unexpected type: {}",
                    result != null ? result.getClass().getName() : "null");
            return null;
        }

        Map<String, Object> vitals = (Map<String, Object>) rawMap;

        double lcp = toDouble(vitals.get("lcp"));
        double cls = toDouble(vitals.get("cls"));
        double fcp = toDouble(vitals.get("fcp"));
        double ttfb = toDouble(vitals.get("ttfb"));

        // SPA stale LCP detection
        String threadName = Thread.currentThread().getName();
        Double previousLcp = previousLcpByThread.get(threadName);
        if (previousLcp != null && Double.compare(previousLcp, lcp) == 0 && lcp > 0) {
            log.debug("BPM: LCP unchanged ({} ms) — SPA stale detection, skipping.", lcp);
            // Still update other vitals but mark LCP as null via convention
            previousLcpByThread.put(threadName, lcp);
            return null;
        }
        previousLcpByThread.put(threadName, lcp);

        return new WebVitalsResult(
                Math.round(fcp),
                Math.round(lcp),
                cls,
                Math.round(ttfb)
        );
    }

    /**
     * Resets the per-thread LCP tracking state. Called during {@code testStarted()}.
     */
    public void reset() {
        previousLcpByThread.clear();
    }

    /**
     * Safely converts a JavaScript number result to double.
     */
    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }
}
