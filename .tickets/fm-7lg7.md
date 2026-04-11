---
id: fm-7lg7
status: closed
deps: []
links: []
created: 2026-04-11T23:19:40Z
type: bug
priority: 0
assignee: SteffenPL
external-ref: feedback-1
tags: [feedback, bridge, execution]
---
# Macro cancellation is cosmetic (stuck worker thread)

Group I Bug A: Future.cancel + Macro.abort did not stop IJ macros. Fixed in ca47ab1 by driving Interpreter directly and using interp.abortMacro as the cancel hook.

