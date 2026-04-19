package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.io.FileSaver;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

import java.awt.Rectangle;
import java.io.File;

public class ImageService {

    private EventEmitter eventEmitter;

    public void setEventEmitter(EventEmitter emitter) {
        this.eventEmitter = emitter;
    }

    public JsonObject listImages() {
        JsonArray images = new JsonArray();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    JsonObject img = new JsonObject();
                    img.addProperty("id", id);
                    img.addProperty("title", imp.getTitle());
                    img.addProperty("width", imp.getWidth());
                    img.addProperty("height", imp.getHeight());
                    images.add(img);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.add("images", images);
        ImagePlus activeImp = WindowManager.getCurrentImage();
        if (activeImp != null) {
            result.addProperty("active", activeImp.getTitle());
        }
        return result;
    }

    public JsonObject getImageInfo(JsonObject params) {
        ImagePlus imp = findImageOrThrow(params);
        JsonObject result = new JsonObject();
        result.addProperty("title", imp.getTitle());
        result.addProperty("width", imp.getWidth());
        result.addProperty("height", imp.getHeight());
        result.addProperty("depth", imp.getNSlices());
        result.addProperty("channels", imp.getNChannels());
        result.addProperty("frames", imp.getNFrames());
        result.addProperty("type", typeName(imp.getType()));
        if (imp.getOriginalFileInfo() != null
                && imp.getOriginalFileInfo().directory != null) {
            result.addProperty("path",
                    imp.getOriginalFileInfo().directory
                    + imp.getOriginalFileInfo().fileName);
        }
        return result;
    }

    public JsonObject saveImage(JsonObject params) {
        String title = params.get("title").getAsString();
        String format = params.has("format")
                ? params.get("format").getAsString() : "tiff";
        ImagePlus imp = WindowManager.getImage(title);
        if (imp == null) {
            throw new RuntimeException("Image not found: " + title);
        }

        String path;
        if (params.has("path")) {
            path = params.get("path").getAsString();
        } else {
            String fmt = format.toLowerCase();
            String ext;
            if ("png".equals(fmt)) {
                ext = ".png";
            } else if ("jpg".equals(fmt) || "jpeg".equals(fmt)) {
                ext = ".jpg";
            } else {
                ext = ".tif";
            }
            path = System.getProperty("java.io.tmpdir") + File.separator
                    + "fiji_mcp_" + System.currentTimeMillis() + ext;
        }

        FileSaver saver = new FileSaver(imp);
        String fmtLower = format.toLowerCase();
        boolean ok;
        if ("png".equals(fmtLower)) {
            ok = saver.saveAsPng(path);
        } else if ("jpg".equals(fmtLower) || "jpeg".equals(fmtLower)) {
            ok = saver.saveAsJpeg(path);
        } else {
            ok = saver.saveAsTiff(path);
        }
        if (!ok) {
            throw new RuntimeException("Failed to save image");
        }

        // Notify event emitter for image_saved event
        if (eventEmitter != null) {
            eventEmitter.notifyImageSaved(title, path);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("format", format);
        return result;
    }

    public JsonObject getThumbnail(JsonObject params) {
        ImagePlus imp = findImageOrThrow(params);

        int maxSize = params.has("max_size")
                ? params.get("max_size").getAsInt() : 800;
        boolean applyLut = !params.has("apply_lut")
                || params.get("apply_lut").getAsBoolean();

        // flatten() bakes the current LUT, overlays, and ROIs into an RGB
        ImagePlus snapshot;
        if (applyLut) {
            snapshot = imp.flatten();
        } else {
            snapshot = new ImagePlus("thumb", imp.getProcessor().duplicate());
        }

        // Scale down, preserving aspect ratio
        int w = snapshot.getWidth(), h = snapshot.getHeight();
        if (w > maxSize || h > maxSize) {
            double scale = (double) maxSize / Math.max(w, h);
            int nw = (int) (w * scale), nh = (int) (h * scale);
            snapshot.setProcessor(
                    snapshot.getProcessor().resize(nw, nh, true));
        }

        String path = System.getProperty("java.io.tmpdir") + File.separator
                + "fiji_mcp_thumb_" + System.currentTimeMillis() + ".png";
        new FileSaver(snapshot).saveAsPng(path);

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("width", snapshot.getProcessor().getWidth());
        result.addProperty("height", snapshot.getProcessor().getHeight());
        result.addProperty("is_inverted", imp.isInvertedLut());
        return result;
    }

    public JsonObject getResultsTable(JsonObject params) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null || rt.size() == 0) {
            JsonObject result = new JsonObject();
            result.addProperty("rows", 0);
            result.addProperty("message", "No results table available");
            return result;
        }

        String path;
        if (params.has("path")) {
            path = params.get("path").getAsString();
        } else {
            path = System.getProperty("java.io.tmpdir") + File.separator
                    + "fiji_results_" + System.currentTimeMillis() + ".csv";
        }

        try {
            rt.save(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save results: " + e.getMessage(), e);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.addProperty("rows", rt.size());
        return result;
    }

    public JsonObject getRoiManager() {
        RoiManager rm = RoiManager.getInstance();
        JsonObject result = new JsonObject();
        if (rm == null || rm.getCount() == 0) {
            result.addProperty("count", 0);
            result.add("rois", new JsonArray());
            return result;
        }

        Roi[] rois = rm.getRoisAsArray();
        result.addProperty("count", rois.length);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < rois.length; i++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", i);
            entry.addProperty("name", rm.getName(Integer.toString(i)));
            entry.addProperty("type", roiTypeName(rois[i].getType()));
            Rectangle bounds = rois[i].getBounds();
            JsonObject b = new JsonObject();
            b.addProperty("x", bounds.x);
            b.addProperty("y", bounds.y);
            b.addProperty("width", bounds.width);
            b.addProperty("height", bounds.height);
            entry.add("bounds", b);
            arr.add(entry);
        }
        result.add("rois", arr);
        return result;
    }

    /**
     * Snapshot the first {@code maxRows} rows and {@code maxCols} columns of
     * the current Results table as a JsonObject with "rows", "columns", and
     * "data" (array of row-arrays).  Returns null when the table is empty.
     * Used by ExecutionReporter to embed a results preview in the envelope.
     */
    /**
     * Snapshot the Results table, showing only rows from {@code startRow}
     * onwards (i.e. the rows added during the current execution).
     *
     * @param maxRows  maximum number of rows to include in the preview
     * @param maxCols  maximum number of columns to include
     * @param startRow first row to include (0-based); rows before this are
     *                 from a prior execution and are excluded from data[]
     */
    public static JsonObject snapshotResultsTable(int maxRows, int maxCols, int startRow) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null || rt.size() == 0) return null;

        int total = rt.size();
        int effectiveStart = Math.max(0, Math.min(startRow, total));
        int newRows = total - effectiveStart;
        if (newRows <= 0) return null;

        String[] allHeadings = rt.getHeadings();
        int cols = Math.min(allHeadings.length, maxCols);
        int rows = Math.min(newRows, maxRows);

        JsonArray columns = new JsonArray();
        for (int c = 0; c < cols; c++) columns.add(allHeadings[c]);

        JsonArray data = new JsonArray();
        for (int r = effectiveStart; r < effectiveStart + rows; r++) {
            JsonArray row = new JsonArray();
            for (int c = 0; c < cols; c++) {
                String sv = rt.getStringValue(allHeadings[c], r);
                if (sv != null && !sv.isEmpty()) {
                    row.add(sv);
                } else {
                    row.add(rt.getValueAsDouble(rt.getColumnIndex(allHeadings[c]), r));
                }
            }
            data.add(row);
        }

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("total_rows", total);
        snapshot.addProperty("new_rows", newRows);
        snapshot.add("columns", columns);
        snapshot.add("data", data);
        if (newRows > maxRows) {
            snapshot.addProperty("truncated", true);
        }
        if (allHeadings.length > maxCols) {
            snapshot.addProperty("truncated_columns", true);
            snapshot.addProperty("total_columns", allHeadings.length);
        }
        return snapshot;
    }

    /**
     * Returns the current Results table row count (0 if null/empty).
     */
    public static int resultsTableRowCount() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt == null ? 0 : rt.size();
    }

    public JsonObject getLog(JsonObject params) {
        int count = params.has("count") ? params.get("count").getAsInt() : 50;
        String logText = IJ.getLog();

        JsonArray lines = new JsonArray();
        if (logText != null && !logText.isEmpty()) {
            String[] allLines = logText.split("\n");
            int start = Math.max(0, allLines.length - count);
            for (int i = start; i < allLines.length; i++) {
                lines.add(allLines[i]);
            }
        }

        JsonObject result = new JsonObject();
        result.add("lines", lines);
        result.addProperty("total", lines.size());
        return result;
    }

    private ImagePlus findImageOrThrow(JsonObject params) {
        if (params.has("title")) {
            String title = params.get("title").getAsString();
            ImagePlus imp = WindowManager.getImage(title);
            if (imp == null) {
                throw new RuntimeException(
                        "Image not found: \"" + title + "\". " + availableTitlesHint());
            }
            return imp;
        }
        if (params.has("id")) {
            int id = params.get("id").getAsInt();
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) {
                throw new RuntimeException(
                        "Image not found with id " + id + ". " + availableTitlesHint());
            }
            return imp;
        }
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            throw new RuntimeException("No active image. " + availableTitlesHint());
        }
        return imp;
    }

    private String availableTitlesHint() {
        int[] ids = WindowManager.getIDList();
        if (ids == null || ids.length == 0) {
            return "No images are open.";
        }
        StringBuilder sb = new StringBuilder("Open images: [");
        for (int i = 0; i < ids.length; i++) {
            ImagePlus img = WindowManager.getImage(ids[i]);
            if (i > 0) sb.append(", ");
            sb.append("\"").append(img != null ? img.getTitle() : "?").append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String typeName(int type) {
        switch (type) {
            case ImagePlus.GRAY8:      return "8-bit";
            case ImagePlus.GRAY16:     return "16-bit";
            case ImagePlus.GRAY32:     return "32-bit";
            case ImagePlus.COLOR_256:  return "8-bit color";
            case ImagePlus.COLOR_RGB:  return "RGB";
            default:                   return "unknown";
        }
    }

    private String roiTypeName(int type) {
        switch (type) {
            case Roi.RECTANGLE:    return "rectangle";
            case Roi.OVAL:         return "oval";
            case Roi.POLYGON:      return "polygon";
            case Roi.FREEROI:      return "freehand";
            case Roi.TRACED_ROI:   return "traced";
            case Roi.LINE:         return "line";
            case Roi.POLYLINE:     return "polyline";
            case Roi.FREELINE:     return "freeline";
            case Roi.ANGLE:        return "angle";
            case Roi.COMPOSITE:    return "composite";
            case Roi.POINT:        return "point";
            default:               return "unknown";
        }
    }
}
