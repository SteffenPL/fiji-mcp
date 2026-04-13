---
id: fm-0etp
status: open
deps: []
links: [fm-kfhn]
created: 2026-04-13T00:49:58Z
type: feature
priority: 1
assignee: SteffenPL
tags: [evals, bridge]
---
# Eval harness v0: runner + first two checkable tasks

Build the minimal eval harness that can run closed-form Fiji tasks against an LLM through the MCP bridge and report objective metrics.

## Runner shape
- Shell script or Python CLI that: (1) launches Fiji via launch-fiji-bridge.sh, (2) waits for bridge via fiji-health.sh, (3) runs each task sequentially, (4) outputs one JSON row per (task, model) with metrics.
- Each task is a directory under evals/ with: prompt.md (the instruction given to the LLM), setup.py (loads fixture image, clears state via MCP), check.py (asserts on the LLM's final answer via MCP).
- The runner uses the real MCP protocol — no bypassing the bridge.

## First two tasks
1. **Spot counting**: provide a fluorescence image, ask 'how many local maxima with prominence >= N'. Ground truth = reference count. Tests: run_ij_macro, get_results_table.
2. **Threshold + area measurement**: 'total area of foreground above threshold T in pixels'. Closed-form numeric answer. Tests: run_ij_macro, get_image_info.

## Metrics per task per run
- success (0/1): final answer matches ground truth within tolerance
- tool_call_count: how many MCP round trips
- wall_clock_s: total time
- error_count: how many envelopes came back with non-null error

## Out of scope for v0
- Context token tracking (needs model API integration)
- Duplicate-pair detection (later refinement)
- Parallel task execution
- CI integration (needs headless, fm-kflp)

