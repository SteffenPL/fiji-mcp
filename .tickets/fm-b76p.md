---
id: fm-b76p
status: closed
deps: []
links: [fm-hjam]
created: 2026-04-17T06:40:06Z
type: task
priority: 4
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, polish]
---
# list_images should indicate the active image

Session 2 feedback (minor): the execution envelope has active_image identifying the frontmost image, but list_images does not surface it. After a few tool calls I would re-check list_images to see the current titles and then have to cross-reference the last envelopes to remember which window was active.

Fix: either add is_active: true on the matching entry, or a top-level active: "name" field in the list_images response.

Probably subsumed by fm-hjam (get_image_state snapshot tool) in the long run, but this is a one-line addition to an existing tool and would remove the cross-reference.

