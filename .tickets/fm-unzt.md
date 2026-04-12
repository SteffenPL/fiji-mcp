---
id: fm-unzt
status: closed
deps: []
links: [fm-bpjo]
created: 2026-04-11T23:19:40Z
type: bug
priority: 2
assignee: SteffenPL
external-ref: feedback-1
tags: [feedback, bridge, execution]
---
# Detect modal dialogs blocking the worker thread

Session 1 item 5: Find Maxima hung on a modal dialog with no diagnostic. Partially mitigated by setIgnoreErrors+Interpreter path, but non-macro dialog sources can still block.


## Notes

**2026-04-12T00:59:16Z**

Fresh repro 2026-04-12 during fp-89zk live testing: run_ij_macro with code 'IJ.run("Convert to Mask")' against an empty Fiji session (no active image) blocked the worker thread for 87 seconds before returning 'Macro canceled'. The hard timeout is 600s and no soft timeout was set, so something internal cancelled it after 87s — possibly Fiji's own dialog auto-dismiss or an interrupt from another path. Either way: the worker was wedged on a modal dialog with no diagnostic, exactly the failure mode this ticket describes. The fp-89zk fix doesn't help here because nothing throws — the worker just blocks on Swing/AWT dialog input.

**2026-04-12T03:47:08Z**

Fixed via DialogWatchdog (polling, snapshot-diff, capped at 20) + DialogDismissedException error promotion + dismissed_dialogs[] envelope field. ExecutionLock shipped but is a no-op — ij.ImageJ is java.awt.Frame not JFrame. Filed follow-up fu-fyky. Live-verified: dialogs dismissed in ~500ms (was 87s), user dialogs protected by snapshot, cascade capped at 20.
