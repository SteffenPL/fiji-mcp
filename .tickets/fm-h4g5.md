---
id: fm-h4g5
status: open
deps: []
links: []
created: 2026-04-11T23:27:10Z
type: bug
priority: 2
assignee: SteffenPL
external-ref: feedback-4
tags: [feedback, bridge, events]
---
# SourceTracker: MCP-triggered events are misclassified as source=user

Session 4: every event in get_recent_actions reports source=user even for events fired by run_ij_macro (Duplicate, 8-bit, etc). SourceTracker.setMcpActive(true) is set in ExecutionReporter.runReported but the flag is not reaching the event emission site. Possible thread-locality / EDT dispatch issue. Costs real debugging time by making the agent distrust the event log.

