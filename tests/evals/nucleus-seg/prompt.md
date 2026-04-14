# Task: Nucleus Segmentation

You have access to a running Fiji instance through MCP tools.

## Objective

Segment individual nuclei in a fluorescence microscopy image and save the result as a binary mask.

## Instructions

1. Open the image at `FIXTURE_PATH` using an ImageJ macro.
2. Segment the nuclei. The image shows bright nuclei on a dark background. Your pipeline should:
   - Threshold the image so that bright nuclei become white (255) foreground and the dark background becomes black (0).
   - Split touching or overlapping nuclei so that each nucleus is a separate connected component.
   - Clean up the mask to remove small noise fragments.
3. The final mask must be 8-bit, the same dimensions as the input, with nuclei white (255) and background black (0). If your mask is inverted, fix it before saving.
4. Save the binary mask to `OUTPUT_PATH` using `saveAs("Tiff", "OUTPUT_PATH")` in a macro.

Reply with only the word DONE when finished.
