"""Checker for the nucleus-seg eval task.

Compares an output binary mask against a reference mask.
Computes IoU and connected-component counts.
Writes metrics.json to the output directory.
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import tifffile
from scipy.ndimage import label


def compute_metrics(output_path: str, reference_path: str) -> dict:
    output = tifffile.imread(output_path) > 127
    reference = tifffile.imread(reference_path) > 127

    if output.shape != reference.shape:
        return {
            "task": "nucleus-seg",
            "error": f"Shape mismatch: output {output.shape} vs reference {reference.shape}",
            "success": 0,
        }

    intersection = np.count_nonzero(output & reference)
    union = np.count_nonzero(output | reference)
    iou = intersection / union if union > 0 else 0.0

    _, count_ref = label(reference)
    _, count_out = label(output)

    return {
        "task": "nucleus-seg",
        "iou": round(iou, 4),
        "success": 1 if iou >= 0.7 else 0,
        "cell_count_ref": int(count_ref),
        "cell_count_output": int(count_out),
        "cell_count_diff": abs(int(count_out) - int(count_ref)),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, help="Path to output mask")
    parser.add_argument("--reference", required=True, help="Path to reference mask")
    parser.add_argument("--metrics-dir", default=None, help="Directory to write metrics.json")
    args = parser.parse_args()

    metrics = compute_metrics(args.output, args.reference)

    metrics_dir = Path(args.metrics_dir) if args.metrics_dir else Path(args.output).parent
    metrics_dir.mkdir(parents=True, exist_ok=True)
    metrics_path = metrics_dir / "metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2) + "\n")

    print(json.dumps(metrics, indent=2))
    sys.exit(0 if metrics.get("success") else 1)


if __name__ == "__main__":
    main()
