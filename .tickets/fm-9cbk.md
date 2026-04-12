---
id: fm-9cbk
status: closed
deps: []
links: [fm-50jy]
created: 2026-04-11T23:27:10Z
type: bug
priority: 0
assignee: SteffenPL
external-ref: feedback-4
tags: [feedback, bridge, events, regression]
---
# EventEmitter.roiModified NPEs on detached ROIs from ParticleAnalyzer

Root cause of embryos.jpg COUNT=0 in Session 4. RoiListener.roiModified dereferences imp without null check; ParticleAnalyzer constructs transient PolygonRoi per particle with null imp, NPE aborts analysis mid-pass. Fix: early-return when imp==null (skip detached ROIs entirely, not just null-guard payload). File: fiji-plugin/src/main/java/com/fiji/mcp/bridge/EventEmitter.java:179


## Notes

**2026-04-12T00:58:58Z**

Fixed via early-return when imp==null in EventEmitter.roiModified (fiji-plugin/src/main/java/com/fiji/mcp/bridge/EventEmitter.java). Verified end-to-end: synthetic 6-blob image through Analyze Particles now returns count=6 instead of aborting mid-pass at 0. Regression test: EventEmitterTest.roiModified_withNullImp_doesNotThrowAndEmitsNoEvent.
