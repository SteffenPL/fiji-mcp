---
id: fp-89zk
status: closed
deps: []
links: [fm-50jy]
created: 2026-04-12T00:28:38Z
type: bug
priority: 2
assignee: SteffenPL
tags: [bridge, observability]
---
# IJ.handleException in GUI mode opens TextWindow with no envelope visibility

Discovered while live-testing fm-50jy. Calling IJ.handleException(t) from a script in GUI Fiji opens an 'Exception' TextWindow popup containing the stack trace, but writes nothing to System.err or IJ.log. The trace is invisible to envelope.stderr (no stream touched) and envelope.stdout (no IJ.log touched). Repro: run_script(language='groovy', code='try { throw new NullPointerException("x") } catch(t) { ij.IJ.handleException(t) }; return "after"') — envelope shows stderr="" and stdout="" but a popup appears in Fiji. Distinct from fm-50jy: that ticket fixed the case where stderr is *written*. This case never writes to any stream at all. Likely fixes: (a) install a custom IJ.ExceptionHandler via IJ.setExceptionHandler that writes the trace to System.err (where the StderrTeeStream will catch it) and/or to IJ.log, OR (b) scan Fiji TextWindow registry for windows titled 'Exception' on envelope build and pull their content. Option (a) is much cleaner — one-line install in BridgePlugin.run() right after the System.setErr call. Note: in headless mode IJ.handleException already routes to System.err, so this is GUI-mode-only.


## Notes

**2026-04-12T00:59:11Z**

Fixed by installing BridgeExceptionHandler via IJ.setExceptionHandler in BridgePlugin.run(), right after the StderrTeeStream install. The handler does t.printStackTrace(System.err) which the tee then catches. Original 'Exception' TextWindow popup is intentionally dropped — when the bridge is running the agent surfaces traces via envelope.stderr, and popups would just accumulate noise during automated workflows. Verified live with the exact ticket repro: trace landed in envelope.stderr AND Fiji's Console (passthrough), no Exception popup in WindowManager.getNonImageWindows().
