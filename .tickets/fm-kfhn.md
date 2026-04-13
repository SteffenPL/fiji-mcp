---
id: fm-kfhn
status: open
deps: []
links: [fm-0etp]
created: 2026-04-11T23:27:10Z
type: epic
priority: 2
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, bridge, evals]
---
# Evaluation harness for measuring LLM task performance on the bridge

Closed-form checkable Fiji tasks + runner that records tool-call count, duplicate pairs, wall clock, context burn, error-envelope rate. Originally required headless mode, but with `launch-fiji-bridge.sh` + `fiji-health.sh` the harness can now run against a visible GUI Fiji instance. Headless (fm-kflp) remains desirable for CI but is no longer a hard blocker.

