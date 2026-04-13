# Eval Harness — Orchestrator Prompt

You are running the fiji-mcp evaluation harness. You have access to the fiji-mcp MCP tools and the Bash tool. Follow these steps exactly.

## Configuration

- Project root: the current working directory
- Fixture image: `evals/nucleus-seg/fixtures/nuclei.tif`
- Ground truth macro: `evals-internal/nucleus-seg/ground_truth.ijm`
- Output directory: `evals/results/nucleus-seg/`
- Reference mask path: `/tmp/fiji-eval/nucleus-seg/ground_truth.tif`
- Output mask path: `evals/results/nucleus-seg/output_mask.tif`

## Step 1: Verify bridge

Run `./fiji-health.sh` via Bash. If it exits non-zero, print "Bridge not ready — start Fiji with ./launch-fiji-bridge.sh first" and stop.

## Step 2: Generate ground truth

Use the fiji-mcp tools to:

1. Create the temp directory: `mkdir -p /tmp/fiji-eval/nucleus-seg`
2. Open the fixture image via `run_ij_macro`:
   ```
   open("<absolute path to evals/nucleus-seg/fixtures/nuclei.tif>");
   ```
3. Read the ground truth macro from `evals-internal/nucleus-seg/ground_truth.ijm` and run it via `run_ij_macro`.
4. Save the result via `run_ij_macro`:
   ```
   saveAs("Tiff", "/tmp/fiji-eval/nucleus-seg/ground_truth.tif");
   ```
5. Close all images via `run_ij_macro`:
   ```
   close("*");
   ```

## Step 3: Reset Fiji state

Run via `run_ij_macro`:
```
close("*");
run("Clear Results");
```

## Step 4: Spawn subagent

1. Read the task prompt from `evals/nucleus-seg/prompt.md`.
2. In the prompt text, replace `FIXTURE_PATH` with the absolute path to `evals/nucleus-seg/fixtures/nuclei.tif` and `OUTPUT_PATH` with the absolute path to `evals/results/nucleus-seg/output_mask.tif`.
3. Create the output directory: `mkdir -p evals/results/nucleus-seg`
4. Record the current time.
5. Spawn a subagent (using the Agent tool) with the prepared prompt. The subagent will use the fiji-mcp MCP tools to complete the segmentation task.
6. When the subagent finishes, record the elapsed wall-clock time.

## Step 5: Run checker

Run via Bash:
```
uv run python evals/nucleus-seg/check.py \
  --output evals/results/nucleus-seg/output_mask.tif \
  --reference /tmp/fiji-eval/nucleus-seg/ground_truth.tif
```

Read the output to get the metrics.

## Step 6: Summarize

1. Read `evals/results/nucleus-seg/metrics.json`.
2. Add `wall_clock_s` from your timing in step 4.
3. Write the final summary to `evals/results/summary.json` with this structure:
   ```json
   {
     "run_date": "<today's date>",
     "tasks": [
       {
         "task": "nucleus-seg",
         "iou": <from metrics>,
         "success": <from metrics>,
         "cell_count_diff": <from metrics>,
         "wall_clock_s": <elapsed seconds>
       }
     ]
   }
   ```
4. Print the summary in a readable format.
