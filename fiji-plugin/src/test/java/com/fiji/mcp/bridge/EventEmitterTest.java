package com.fiji.mcp.bridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventEmitterTest {

    /**
     * Regression for fm-9cbk: ParticleAnalyzer constructs transient PolygonRoi
     * with null imp and fires roiModified through Roi.notifyListeners. The old
     * code dereferenced imp.getRoi() unconditionally, NPE'd, and aborted the
     * analysis mid-pass. Detached ROIs must be skipped entirely (no event).
     */
    @Test
    void roiModified_withNullImp_doesNotThrowAndEmitsNoEvent() {
        EventEmitter emitter = new EventEmitter();
        List<String> received = new ArrayList<>();
        emitter.setEventSink(received::add);

        assertDoesNotThrow(() ->
                emitter.roiModified(null, ij.gui.RoiListener.CREATED));
        assertDoesNotThrow(() ->
                emitter.roiModified(null, ij.gui.RoiListener.MODIFIED));
        assertDoesNotThrow(() ->
                emitter.roiModified(null, ij.gui.RoiListener.DELETED));

        assertTrue(received.isEmpty(),
                "Detached-ROI events must be skipped, not just null-guarded");
    }
}
