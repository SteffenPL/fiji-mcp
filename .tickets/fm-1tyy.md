---
id: fm-1tyy
status: open
deps: []
links: []
created: 2026-04-15T03:03:15Z
type: feature
priority: 3
assignee: SteffenPL
external-ref: feedback-5
tags: [feedback, bridge, ux]
---
# get_thumbnail: list available titles in error on mismatch

When get_thumbnail (or get_image_info) is called with a title that doesn't match any open image, the error is just 'Image not found'. Improve findImage in ImageService.java to include available window titles in the error message, e.g. 'Image not found: "foo". Open images: ["blobs.tif", "Drawing of blobs"]'. This saves agents a list_images round-trip on title guessing failures. The active-image fallback already works when no title/id is passed.

