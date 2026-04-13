# Task: Nucleus Segmentation

You have access to a running Fiji instance through MCP tools.

## Objective

Segment the nuclei in a DAPI fluorescence image and save the result as a binary mask.

## Instructions

1. Open the image at `FIXTURE_PATH` using an ImageJ macro.
2. Segment the nuclei. The image shows bright nuclei on a dark background.
3. Produce a binary mask where nuclei are white (255) and background is black (0). The mask must be 8-bit and the same dimensions as the input.
4. Save the binary mask to `OUTPUT_PATH` using `saveAs("Tiff", "OUTPUT_PATH")` in a macro.

Reply with only the word DONE when finished.
