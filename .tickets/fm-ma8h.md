---
id: fm-ma8h
status: closed
deps: []
links: [fm-b7pp]
created: 2026-04-15T03:03:11Z
type: feature
priority: 2
assignee: SteffenPL
external-ref: feedback-5
tags: [feedback, bridge, return-path]
---
# Add get_roi_manager tool returning ROI count, names, and bounds

New MCP tool: get_roi_manager. Returns inline JSON with ROI count, names, types, and bounding boxes from the ROI Manager. Follows the inline-tabular-return convention (fm-b7pp). Also consider embedding roi_count in the execution envelope when the ROI Manager changes (similar to results_snapshot in the envelope ticket). Java side: new method in ImageService or a new RoiService. Python side: new forwarded tool in server.py.

