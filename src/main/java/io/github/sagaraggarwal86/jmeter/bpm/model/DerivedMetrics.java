package io.github.sagaraggarwal86.jmeter.bpm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * Derived performance diagnostics computed from the four raw metric tiers
 * at zero additional CDP overhead.
 *
 * <p>Corresponds to the {@code derived} field in the JSONL schema (section 4.2).
 * Computed by {@link io.github.sagaraggarwal86.jmeter.bpm.collectors.DerivedMetricsCalculator}.
 *
 * <h2>Bottleneck detection</h2>
 * <p>Detection runs top-down; the <em>first</em> matching condition becomes the
 * primary {@link #bottleneck()} label (shown in the GUI table). All matching
 * conditions are recorded in {@link #bottlenecks()} for JAAR AI analysis.
 * When no condition matches, {@code bottleneck} is {@code "—"} and
 * {@code bottlenecks} is empty.
 *
 * <h2>Server ratio display format</h2>
 * <p>{@link #serverClientRatio()} is stored as a {@code double} rounded to two
 * decimal places at computation time (e.g., {@code 32.20}). GUI and log output
 * must append the {@code %} suffix when rendering (e.g., {@code "32.20%"}).
 *
 * <h2>Performance score ranges</h2>
 * <ul>
 *   <li>Good: ≥ 90 (green)</li>
 *   <li>Needs Work: 50–89 (amber)</li>
 *   <li>Poor: &lt; 50 (red)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "renderTime", "serverClientRatio", "fcpLcpGap",
        "failedRequestRate", "bottleneck", "bottlenecks", "performanceScore"
})
public record DerivedMetrics(

        /**
         * Pure client-side rendering duration in milliseconds.
         * Formula: {@code LCP − TTFB}.
         * Isolates rendering work from server response time.
         */
        @JsonProperty("renderTime") long renderTime,

        /**
         * Percentage of total LCP time attributable to server response.
         * Formula: {@code (TTFB ÷ LCP) × 100}, rounded to two decimal places.
         * Higher values indicate the server is the primary bottleneck.
         * GUI and log output must render this as {@code "32.20%"} format.
         */
        @JsonProperty("serverClientRatio") double serverClientRatio,

        /**
         * Gap between First Contentful Paint and Largest Contentful Paint, in ms.
         * Formula: {@code LCP − FCP}.
         * A large gap indicates lazy-loaded or render-blocking content delaying
         * the main element.
         */
        @JsonProperty("fcpLcpGap") long fcpLcpGap,

        /**
         * Percentage of network requests that failed (4xx/5xx or connection error).
         * Formula: {@code (failedRequests ÷ totalRequests) × 100}.
         * Zero when no requests failed or when network data is unavailable.
         */
        @JsonProperty("failedRequestRate") double failedRequestRate,

        /**
         * Primary performance bottleneck label (first-match-wins from detection table).
         * Possible values: {@code "Reliability issue"}, {@code "Server bottleneck"},
         * {@code "Resource bottleneck"}, {@code "Client rendering"},
         * {@code "Layout thrashing"}, or {@code "—"} when no bottleneck is detected.
         */
        @JsonProperty("bottleneck") String bottleneck,

        /**
         * All matching bottleneck labels in detection priority order.
         * Empty list when no bottleneck is detected.
         * Included in JSONL for JAAR AI correlation; not shown in GUI table
         * (which shows only the primary {@link #bottleneck()} label).
         */
        @JsonProperty("bottlenecks") List<String> bottlenecks,

        /**
         * Composite performance score from 0 (worst) to 100 (best).
         * Weighted average of scored thresholds:
         * LCP 40%, FCP 15%, CLS 15%, TTFB 15%, JS errors 15%.
         * See design document section 3.3 for full scoring table.
         */
        @JsonProperty("performanceScore") int performanceScore

) {
    /**
     * Compact constructor that defensively copies {@code bottlenecks} and
     * normalises {@code null} to an empty list.
     *
     * @param renderTime        client rendering duration in ms (≥ 0)
     * @param serverClientRatio server share of LCP, rounded to 2 decimal places
     * @param fcpLcpGap         FCP-to-LCP gap in ms (≥ 0)
     * @param failedRequestRate failed request percentage (0.0–100.0)
     * @param bottleneck        primary bottleneck label; never {@code null}
     * @param bottlenecks       all matching bottleneck labels; {@code null} treated as empty
     * @param performanceScore  composite score in range [0, 100]
     */
    public DerivedMetrics {
        bottlenecks = bottlenecks != null ? List.copyOf(bottlenecks) : List.of();
    }
}
