---
id: fm-iceo
status: closed
deps: []
links: []
created: 2026-04-17T06:39:45Z
type: feature
priority: 3
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, bridge, discovery]
---
# Expose installed plugin catalog for agent discovery

Session 2 feedback: I did not know MorphoLibJ was installed until I happened to run list_commands pattern="watershed" and noticed Classic Watershed / Distance Transform Watershed / Marker-controlled Watershed with inra.ijpb.plugins.* class names.

Before that I was designing around stdlib ImageJ ops (run("Watershed"), EDM + Find Maxima) and only reached for MorphoLibJ late, after several iterations. Strategic knowledge of what is available would have changed algorithm choice upfront — Marker-controlled Watershed is fundamentally different from an IJ1 Find Maxima approach and is the right answer for dense touching-blob segmentation.

Proposals, cheapest first:
(a) Extend get_fiji_info to include detected_plugin_packages: ["inra.ijpb", "sc.fiji.trackmate", "de.mpicbg.scf", ...] — detect by scanning loaded classes for well-known prefixes.
(b) New tool list_plugins(filter?) → [{name, version, jar}] — more thorough, higher cost to build.
(c) Prime the MCP server instructions block at handshake time with "you have MorphoLibJ / TrackMate / ..." if detected.

(a) is probably the right v1; (c) is also interesting because it changes default reasoning rather than requiring a separate query.

Relates to CLAUDE.md design principle "scripting-first, no curated wrappers" — this is discovery for scripting, not wrappers.

