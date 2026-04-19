---
id: fm-yfob
status: open
deps: []
links: []
created: 2026-04-17T06:39:30Z
type: bug
priority: 3
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, bridge, observability]
---
# results_snapshot.new_rows semantics are confusing after Clear Results

Session 2 feedback: I repeatedly misinterpreted new_rows in results_snapshot.

In a single macro I called run("Clear Results") near the top, ran a pipeline, then run("Analyze Particles..."). The envelope came back as {"total_rows": 63, "new_rows": 4} where I expected either {total: 63, new: 63} (Clear zeroes the baseline so everything is "new") or a field that explained the discrepancy.

Likely cause: Find Maxima with output=[Segmented Particles] quietly adds rows before the AnalyzeParticles call, and new_rows only counts deltas produced by the *last* row-producing command rather than the script as a whole. So 59 rows came from the hidden Find Maxima call and 4 from AnalyzeParticles.

Fix options:
(a) Document the exact semantics ("new_rows = rows added by the terminal row-producing command").
(b) Count from macro start to macro end, so Clear Results correctly zeroes the baseline and new_rows reflects net additions across the whole script.
(c) Return both: {total, new_in_script, new_from_last_command}.

I would pick (b) — it matches naive intent. (a) is the cheap version if (b) is hard.

Related: fm-61gs (which added the snapshot field in the first place).

