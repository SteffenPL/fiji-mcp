---
id: fm-9cbk
status: open
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

