"""Generate a synthetic DAPI fluorescence image with ~30 bright nuclei."""

import numpy as np
from scipy.ndimage import gaussian_filter
import tifffile

rng = np.random.default_rng(42)

W, H = 512, 512
img = np.zeros((H, W), dtype=np.float64)

# Place ~30 nuclei as bright disks with random radii
n_nuclei = 30
centers = []
for _ in range(n_nuclei):
    cx = rng.integers(40, W - 40)
    cy = rng.integers(40, H - 40)
    radius = rng.integers(8, 18)
    intensity = rng.uniform(0.6, 1.0)
    yy, xx = np.ogrid[:H, :W]
    mask = ((xx - cx) ** 2 + (yy - cy) ** 2) <= radius**2
    img[mask] = np.maximum(img[mask], intensity)
    centers.append((cx, cy))

# Blur to simulate fluorescence PSF
img = gaussian_filter(img, sigma=3.0)

# Add Poisson-like noise
img = img + rng.normal(0, 0.03, img.shape)
img = np.clip(img, 0, 1)

# Convert to 8-bit
img_8bit = (img * 255).astype(np.uint8)

tifffile.imwrite("nuclei.tif", img_8bit)
print(f"Generated nuclei.tif ({W}x{H}, {n_nuclei} nuclei)")
