---
id: fm-4yxu
status: open
deps: []
links: []
created: 2026-04-17T06:39:58Z
type: feature
priority: 3
assignee: SteffenPL
external-ref: feedback-2
tags: [feedback, bridge, tools]
---
# Add measure_roi and profile_plot primitives for quick data peeks

Session 2 feedback: During segmentation parameter tuning I repeatedly wanted to answer questions like "what is the intensity profile across this peanut blob?" or "what is the mean/min/max in this ROI?" Both forced me to write Groovy to read pixel values directly, which is a heavy tool for a quick diagnostic poke.

Concretely during the blob #22 tuning loop I needed to know the actual dip depth between the two halves to pick a Find Maxima prominence value. I wrote ~30 lines of Groovy to extract pixel values along a line; a primitive would have been one call.

Proposals:
- profile_plot(x1, y1, x2, y2, image_id?) → {distances: [...], values: [...], png_path}
  Mirrors Analyze > Plot Profile. Returns raw data for the agent and a thumbnail for visual inspection.
- measure(roi_index? | bounds?, image_id?) → {area, mean, min, max, stddev, integrated_density, centroid, ...}
  Mirrors Analyze > Measure but operates on a specific target and does NOT append to the shared Results table (avoids statefulness issues).

Value: turns "what does the data actually look like at this spot?" from a scripting task into a probe. Matches the thumbnail/get_image_info ergonomics — cheap, targeted, read-only.

Design constraint: neither should mutate the shared Results table or ROI Manager. Ideally both accept an image_id so they target the intended image rather than whatever is frontmost.

