---
id: fm-bpjo
status: open
deps: []
links: [fm-unzt]
created: 2026-04-12T03:47:11Z
type: bug
priority: 3
assignee: SteffenPL
tags: [bridge, ui]
---
# ExecutionLock glass pane doesn't work on AWT Frame (ij.ImageJ)

ij.ImageJ extends java.awt.Frame not javax.swing.JFrame — glass pane never installed. frame.getMenuBar() returns null on macOS. ExecutionLock is a no-op. Fix: replace glass pane with undecorated transparent Window overlaid on Frame bounds. Watchdog works independently.

