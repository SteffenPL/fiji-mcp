package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.measure.ResultsTable;

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
        return result;
    }

    public JsonObject getImageInfo(JsonObject params) {
        ImagePlus imp = findImage(params);
        if (imp == null) {
            throw new RuntimeException("Image not found");
        }
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
        ImagePlus imp = findImage(params);
        if (imp == null) {
            throw new RuntimeException("Image not found");
        }

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

    private ImagePlus findImage(JsonObject params) {
        if (params.has("title")) {
            return WindowManager.getImage(params.get("title").getAsString());
        }
        if (params.has("id")) {
            return WindowManager.getImage(params.get("id").getAsInt());
        }
        return WindowManager.getCurrentImage();
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
}
