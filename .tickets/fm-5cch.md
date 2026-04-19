---
id: fm-5cch
status: open
deps: []
links: []
created: 2026-04-17T06:39:17Z
type: bug
priority: 1
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, bridge, observability]
---
# dismissed_dialogs should capture dialog body text, not just title

Session 2 feedback (LLM agent, blob segmentation workflow).

When macros error into modal dialogs, envelope.dismissed_dialogs currently returns entries like {"title":"Macro Error","text":"","when_ms":...}. The text field is empty even though the on-screen dialog contains the actual error message (which line, what was wrong). Without the body, the agent knows a macro failed but has no signal about what to fix, forcing blind retry-and-narrow debugging.

Repro: a run_ij_macro whose body calls Size... or Enhance Contrast on an image that was renamed/closed between select and action. Three to five "Macro Error" dialogs were dismissed in ~30s, envelope reported title="Macro Error" and text="" for every one. Forced me to diff tool calls against get_recent_actions to guess the line.

Distinct from fm-5pcx (which routes thrown macro parser errors into envelope.error) and fp-89zk (which reroutes IJ.handleException via BridgeExceptionHandler). Macro Error dialogs are IJ.error() calls from builtin commands that never throw, so both of those paths miss them.

Likely fix: before the DialogWatchdog dismisses a dialog, scrape its text content — either walk the JDialog component tree for JLabel / TextComponent children, or for IJ-specific Macro Error dialogs read Interpreter.getErrorMessage() directly. Populate dismissed_dialogs[].text.

