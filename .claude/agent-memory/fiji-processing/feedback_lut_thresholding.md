---
name: LUT-safe thresholding pattern
description: When preview_inverted=true, objects look DARK in display; correct setAutoThreshold keyword is method WITHOUT "dark"
type: feedback
---

When `get_image_info` or `get_thumbnail` returns `preview_inverted: true`:
- Display LUT is inverted: low raw pixel values appear bright, high raw values appear dark
- Objects visually appear DARK on a bright background
- Use `setAutoThreshold("Otsu")` — no `dark` keyword
- The `"dark"` keyword refers to display-space object appearance, and since objects look dark, omitting it is correct

**Why:** The `dark` keyword in ImageJ's setAutoThreshold tells ImageJ that the foreground is dark in *display space*. With an inverted LUT, physically bright objects (high pixel values) appear dark. If you add `dark`, the threshold inverts and selects background instead of objects.

**How to apply:** Always check `preview_inverted` in the get_thumbnail/get_image_info response before writing threshold code. Blobs sample always has preview_inverted=true.
