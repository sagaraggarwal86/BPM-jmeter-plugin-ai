package io.github.sagaraggarwal86.jmeter.bpm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for BpmPropertiesManager.
 */
@DisplayName("BpmPropertiesManager")
class BpmPropertiesManagerTest {

    @TempDir
    Path tempDir;

    private BpmPropertiesManager createManagerWithDir(Path binDir) {
        return new BpmPropertiesManager() {
            @Override
            Path resolvePropertiesPath() {
                return binDir.resolve("bpm.properties");
            }

            @Override
            String getJMeterProperty(String key) {
                return null; // No -J flags in unit tests
            }
        };
    }

    @Test
    @DisplayName("load() auto-generates bpm.properties when file does not exist")
    void load_autoGeneratesWhenMissing() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertTrue(Files.exists(binDir.resolve("bpm.properties")));
    }

    @Test
    @DisplayName("detectVersion() extracts version from header comment")
    void detectVersion_extractsFromHeader() throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Path props = binDir.resolve("bpm.properties");
        Files.writeString(props, "# Browser Performance Metrics (BPM) v1.0\nbpm.debug=false\n");

        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        assertEquals("1.0", mgr.detectVersion(props));
    }

    @Test
    @DisplayName("detectVersion() returns null when no version header found")
    void detectVersion_returnsNullWhenMissing() throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Path props = binDir.resolve("bpm.properties");
        Files.writeString(props, "# some random comment\nbpm.debug=false\n");

        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        assertNull(mgr.detectVersion(props));
    }

    @Test
    @DisplayName("Version mismatch creates backup file and overwrites with new template")
    void load_versionMismatch_backsUpAndOverwrites() throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Path props = binDir.resolve("bpm.properties");
        Files.writeString(props, "# Browser Performance Metrics (BPM) v0.9\nold.key=old\n");

        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertTrue(Files.exists(binDir.resolve("bpm.properties.v0.9.bak")));
        String content = Files.readString(props, StandardCharsets.UTF_8);
        assertTrue(content.contains("v1.0"), "New template should have current version");
    }

    @Test
    @DisplayName("All metric toggles default to true")
    void metricToggles_defaultTrue() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertTrue(mgr.isWebVitalsEnabled());
        assertTrue(mgr.isNetworkEnabled());
        assertTrue(mgr.isRuntimeEnabled());
        assertTrue(mgr.isConsoleEnabled());
    }

    @Test
    @DisplayName("SLA thresholds match Core Web Vitals defaults")
    void slaThresholds_matchDefaults() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertEquals(1800L, mgr.getSlaFcpGood());
        assertEquals(3000L, mgr.getSlaFcpPoor());
        assertEquals(2500L, mgr.getSlaLcpGood());
        assertEquals(4000L, mgr.getSlaLcpPoor());
        assertEquals(0.1, mgr.getSlaClsGood(), 0.001);
        assertEquals(0.25, mgr.getSlaClsPoor(), 0.001);
        assertEquals(800L, mgr.getSlaTtfbGood());
        assertEquals(1800L, mgr.getSlaTtfbPoor());
    }

    @Test
    @DisplayName("getOutputPath() returns default when no -J flag set")
    void getOutputPath_noJFlag_returnsDefault() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertEquals("bpm-results.jsonl", mgr.getOutputPath());
    }

    @Test
    @DisplayName("getOutputPath() returns -J flag value when set")
    void getOutputPath_jFlagSet_returnsJFlagValue() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = new BpmPropertiesManager() {
            @Override
            Path resolvePropertiesPath() {
                return binDir.resolve("bpm.properties");
            }

            @Override
            String getJMeterProperty(String key) {
                return "bpm.output".equals(key) ? "ci-build-123.jsonl" : null;
            }
        };
        mgr.load();

        assertEquals("ci-build-123.jsonl", mgr.getOutputPath());
    }

    @Test
    @DisplayName("isDebugEnabled() returns -J flag value when set")
    void isDebugEnabled_jFlagSet_returnsTrue() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = new BpmPropertiesManager() {
            @Override
            Path resolvePropertiesPath() {
                return binDir.resolve("bpm.properties");
            }

            @Override
            String getJMeterProperty(String key) {
                return "bpm.debug".equals(key) ? "true" : null;
            }
        };
        mgr.load();

        assertTrue(mgr.isDebugEnabled());
    }

    @Test
    @DisplayName("isDebugEnabled() falls back to bpm.properties when -J not set")
    void isDebugEnabled_noJFlag_fallsBackToProperties() {
        Path binDir = tempDir.resolve("bin");
        BpmPropertiesManager mgr = createManagerWithDir(binDir);
        mgr.load();

        assertFalse(mgr.isDebugEnabled());
    }
}
