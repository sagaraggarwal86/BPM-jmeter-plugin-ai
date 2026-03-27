package io.github.sagaraggarwal86.jmeter.bpm.error;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for BpmErrorHandler and LogOnceTracker.
 */
@DisplayName("BpmErrorHandler + LogOnceTracker")
class BpmErrorHandlerTest {

    private LogOnceTracker tracker;
    private BpmErrorHandler handler;

    @BeforeEach
    void setUp() {
        tracker = new LogOnceTracker();
        handler = new BpmErrorHandler(tracker);
    }

    @Test
    @DisplayName("New thread starts in HEALTHY state")
    void newThread_startsHealthy() {
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertFalse(handler.isThreadDisabled("Thread-1"));
    }

    @Test
    @DisplayName("Collection error transitions HEALTHY → RE_INIT_NEEDED")
    void collectionError_transitionsToReInitNeeded() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        assertEquals(BpmErrorHandler.ThreadState.RE_INIT_NEEDED, handler.getThreadState("Thread-1"));
        assertTrue(handler.needsReInit("Thread-1"));
    }

    @Test
    @DisplayName("Session error transitions to DISABLED")
    void sessionError_transitionsToDisabled() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        handler.handleSessionError("Thread-1", new RuntimeException("Re-init failed"));
        assertEquals(BpmErrorHandler.ThreadState.DISABLED, handler.getThreadState("Thread-1"));
        assertTrue(handler.isThreadDisabled("Thread-1"));
    }

    @Test
    @DisplayName("Successful re-init transitions RE_INIT_NEEDED → HEALTHY")
    void reInitSuccess_transitionsBackToHealthy() {
        handler.handleCollectionError("Thread-1", new RuntimeException("CDP error"));
        handler.markReInitSuccess("Thread-1");
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertEquals(1, handler.getReInitCount());
    }

    @Test
    @DisplayName("Thread states are isolated between different threads")
    void threadStates_isolated() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        assertEquals(BpmErrorHandler.ThreadState.RE_INIT_NEEDED, handler.getThreadState("Thread-1"));
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-2"));
    }

    @Test
    @DisplayName("reset() clears all state")
    void reset_clearsAllState() {
        handler.handleCollectionError("Thread-1", new RuntimeException("error"));
        handler.reset();
        assertEquals(BpmErrorHandler.ThreadState.HEALTHY, handler.getThreadState("Thread-1"));
        assertEquals(0, handler.getFailureCount());
        assertEquals(0, handler.getReInitCount());
    }

    // LogOnceTracker tests

    @Test
    @DisplayName("warnOnce logs first occurrence and suppresses second")
    void warnOnce_logsOnceOnly() {
        tracker.warnOnce("Thread-1", "non-chrome", "msg");
        assertTrue(tracker.hasWarned("Thread-1", "non-chrome"));
        assertEquals(1, tracker.getLoggedCount());

        // Second call with same key — should not increase count
        tracker.warnOnce("Thread-1", "non-chrome", "msg2");
        assertEquals(1, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("warnOnce isolates by thread — same key different threads both log")
    void warnOnce_threadIsolation() {
        tracker.warnOnce("Thread-1", "non-chrome", "msg");
        tracker.warnOnce("Thread-2", "non-chrome", "msg");
        assertEquals(2, tracker.getLoggedCount());
    }

    @Test
    @DisplayName("LogOnceTracker reset clears all tracked warnings")
    void trackerReset_clearsAll() {
        tracker.warnOnce("Thread-1", "key", "msg");
        tracker.reset();
        assertFalse(tracker.hasWarned("Thread-1", "key"));
        assertEquals(0, tracker.getLoggedCount());
    }
}
