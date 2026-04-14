// Ground truth segmentation for the nucleus-seg eval task.
// Expects: the fixture image already open as the active image.
// Produces: a binary mask (255 = nucleus, 0 = background) as the active image.

run("8-bit");
setAutoThreshold("Otsu dark");
run("Convert to Mask");
run("Watershed");
