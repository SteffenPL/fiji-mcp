---
id: fm-61gs
status: open
deps: []
links: [fm-e6fk, fm-b7pp]
created: 2026-04-15T03:03:06Z
type: feature
priority: 1
assignee: SteffenPL
external-ref: feedback-5
tags: [feedback, bridge, return-path]
---
# Include results_snapshot in execution envelope when Results table changes

On macro/script completion, diff the Results table row count vs. before execution. If it changed, embed a 'results_snapshot' field in the completed envelope containing the first 8 columns × 20 rows as JSON. This lets agents see measurement results immediately without a second tool call. Full table remains available via get_results_table for larger datasets. Java side: capture ResultsTable state in ExecutionReporter before/after execution; include snapshot in buildCompleted envelope. Complements fm-e6fk (standalone get_results_table improvement).

