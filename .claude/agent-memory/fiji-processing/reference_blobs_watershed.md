---
name: Blobs watershed benchmark
description: Results from 10-variant EDM watershed segmentation on ImageJ Blobs (25K) sample image; establishes baseline counts and best method
type: reference
---

## Image properties
- Blobs (25K): 256x254, 8-bit, preview_inverted=true (dark blobs on bright background)
- Load via: `run("Blobs (25K)")`

## 10-variant benchmark results

| ID | Method | Pre-processing | Count |
|----|--------|---------------|-------|
| V01 | Otsu + EDM watershed | none | 62 |
| V02 | Otsu + EDM watershed | Gaussian σ=1 | **63 (BEST)** |
| V03 | Otsu + EDM watershed | Gaussian σ=2 | 62 |
| V04 | Triangle + EDM watershed | Gaussian σ=1 | 67 (over-segments) |
| V05 | Li + EDM watershed | Gaussian σ=1 | 63 |
| V06 | MinError + EDM watershed | Gaussian σ=1 | 65 |
| V07 | Otsu + EDM watershed | Gaussian σ=3 | 58 (under-segments) |
| V08 | Otsu + EDM watershed | Median r=2 | 61 |
| V09 | Otsu + EDM watershed | BgSub (rolling=25) + Gσ=1 | 69 (edge artifacts) |
| V10 | Yen + Erode+Dilate + EDM watershed | Gaussian σ=1 | 63 |

## Best method: V02
Otsu threshold + Gaussian σ=1 blur + EDM Watershed. Clean binary, minimal noise, well-separated
blobs. Analyze Particles size filter: 50-Infinity pixels.

## Key observations
- Triangle over-segments (67): biases toward smaller objects in bimodal histograms
- Heavy blur (σ=3) under-segments (58): merges touching blobs before threshold
- Background subtraction with rolling=25 causes edge halos/artifacts on Blobs
- Li and Yen produce counts identical to Otsu with Gσ=1
