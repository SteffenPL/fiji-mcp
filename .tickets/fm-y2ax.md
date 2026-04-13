---
id: fm-y2ax
status: closed
deps: []
links: [fm-meeg]
created: 2026-04-13T00:52:27Z
type: feature
priority: 1
assignee: SteffenPL
tags: [bridge, infrastructure]
---
# Auto-start bridge on Fiji launch for agent-driven workflows

Enable the MCP bridge to auto-start when Fiji launches, with a health-check script so AI agents can autonomously boot and drive Fiji.

## Delivered
- BridgeBootstrap.java: extracted idempotent startup logic from BridgePlugin
- BridgePlugin.java: simplified to delegate to BridgeBootstrap
- launch-fiji-bridge.sh: launches Fiji with -eval 'run("Start Bridge");'
- fiji-health.sh: polls bridge WebSocket readiness with configurable timeout
- launch-fiji.sh: unchanged (no auto-start without -eval)

## Design note
Originally planned as a SciJava Service (BridgeAutoStart) listening for UIShownEvent, but Fiji's plugin discovery did not instantiate the service despite correct META-INF metadata. Pivoted to Fiji's -eval macro flag which triggers BridgePlugin via the IJ1 macro interpreter — simpler, proven reliable.

Spec: docs/superpowers/specs/2026-04-13-autostart-bridge-design.md
Plan: docs/superpowers/plans/2026-04-13-autostart-bridge.md

