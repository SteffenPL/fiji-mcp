---
id: fm-3qpc
status: open
deps: [fm-kflp]
links: []
created: 2026-04-12T03:51:05Z
type: feature
priority: 1
assignee: SteffenPL
tags: [bridge, infrastructure, headless]
---
# Spawn independent headless Fiji instances for parallel MCP work

Launch multiple Fiji instances (headless, each on a separate port) so the agent can work on several ideas in parallel — e.g. exploring different analysis approaches, running parameter sweeps, or prototyping on one instance while another runs a long pipeline. Each instance gets its own bridge WebSocket and its own MCP session. Requires headless Fiji (fm-kflp) as a foundation, plus: port allocation strategy, instance lifecycle management (spawn/kill), and MCP-level routing so the agent can address a specific instance. This is the high-leverage unlock for agentic workflows that today are serialized behind a single worker thread.

