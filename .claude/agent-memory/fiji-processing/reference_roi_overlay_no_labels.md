---
name: ROI overlay without labels (Groovy)
description: Groovy pattern to draw colored ROI outlines on an RGB ImagePlus without numeric labels, using ColorProcessor.draw(roi)
type: reference
---

## Pattern

```groovy
import ij.*
import ij.process.*
import ij.gui.*
import ij.plugin.frame.*
import java.awt.*

ImagePlus original = WindowManager.getImage("myimage.tif")
ImagePlus overlay = original.duplicate()
IJ.run(overlay, "RGB Color", "")
overlay.show()

RoiManager rm = RoiManager.getInstance()
ColorProcessor cp = (ColorProcessor) overlay.getProcessor()
cp.setColor(new Color(255, 200, 0))  // warm yellow

for (int i = 0; i < rm.getCount(); i++) {
    Roi roi = rm.getRoi(i)
    cp.draw(roi)  // draws outline only, no label
}

overlay.updateAndDraw()
IJ.save(overlay, "/path/to/output.png")
```

## Why this approach
- `roiManager("Show All")` + Flatten bakes in labels (ROI numbers) by default
- `Roi.setStrokeColor` + `run("Add Selection...")` approach also renders labels
- Direct `ColorProcessor.draw(roi)` is the cleanest way to render outlines only
- Must convert to RGB before drawing so color is preserved
