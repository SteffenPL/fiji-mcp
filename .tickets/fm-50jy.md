---
id: fm-50jy
status: open
deps: []
links: [fm-9cbk]
created: 2026-04-11T23:27:10Z
type: bug
priority: 0
assignee: SteffenPL
external-ref: feedback-4
tags: [feedback, bridge, observability]
---
# Capture stderr during script execution and surface in envelope

Session 4: NPEs thrown from inside IJ.run command invocations are caught by IJ.handleException, printed to stderr, and swallowed before reaching ScriptExecutor. Envelope returns error:null while full stack trace sits on the terminal. Distinct from Group I Bug B which covered the macro parser path. Fix: per-execution stderr tee in ExecutionReporter.runReported via ThreadLocal-delegating PrintStream. Surface as envelope.stderr or promoted synthetic error.

