---
id: fm-78xw
status: open
deps: []
links: []
created: 2026-04-11T23:19:40Z
type: bug
priority: 3
assignee: SteffenPL
external-ref: feedback-1
tags: [feedback, bridge, state]
---
# Expose lut_inverted in get_image_info

Session 1 item 7: Invert LUT silently broke downstream Convert to Mask. Caller has no way to know display is inverted relative to data.

