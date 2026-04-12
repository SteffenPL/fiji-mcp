---
id: fm-50jy
status: closed
deps: []
links: [fm-9cbk, fp-89zk]
created: 2026-04-11T23:27:10Z
type: bug
priority: 0
assignee: SteffenPL
external-ref: feedback-4
tags: [feedback, bridge, observability]
---
# Capture stderr during script execution and surface in envelope

Session 4: NPEs thrown from inside IJ.run command invocations are caught by IJ.handleException, printed to stderr, and swallowed before reaching ScriptExecutor. Envelope returns error:null while full stack trace sits on the terminal. Distinct from Group I Bug B which covered the macro parser path. Fix: per-execution stderr tee in ExecutionReporter.runReported via ThreadLocal-delegating PrintStream. Surface as envelope.stderr or promoted synthetic error.


## Notes

**2026-04-12T00:59:06Z**

Shipped as StderrTeeStream + ExecutionReporter capture window. First attempt used ThreadLocal-keyed capture, which silently failed in live test because SciJava ScriptService runs script bodies on its own thread pool — capture set on fiji-mcp-worker never saw the script's stderr. Redesigned to a single global active-buffer model (ExecutionReporter already serializes executions, so isolation isn't needed). Tee passes through to the original System.err so Fiji's Console window still shows traces unchanged. Verified live: System.err.println from script, printStackTrace from script, and System.err from a sub-thread spawned inside a script all land in envelope.stderr. Schema change: envelope gains a 'stderr' field on all 4 build* variants (completed/running/in-progress/unknown). Python forwards verbatim — no Python-side changes.
