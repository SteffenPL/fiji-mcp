package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class EventEmitter implements ij.ImageListener, ij.gui.RoiListener {

    private final Set<String> enabledCategories = ConcurrentHashMap.newKeySet();
    private Consumer<String> eventSink;

    // Track tool/color changes via comparison
    private volatile String lastToolName = "";
    private volatile Color lastFgColor = Color.BLACK;
    private volatile Color lastBgColor = Color.WHITE;

    // Track command duration via IJ2 module events
    private final Map<Object, Long> moduleStartTimes = new ConcurrentHashMap<>();

    public EventEmitter() {
        enabledCategories.addAll(Set.of("command", "image", "roi", "log"));
    }

    public void setEventSink(Consumer<String> sink) {
        this.eventSink = sink;
    }

    /**
     * Register IJ1 listeners and subscribe to IJ2 events.
     */
    public void registerListeners(org.scijava.event.EventService eventService) {
        // IJ1 listeners
        ImagePlus.addImageListener(this);
        Roi.addRoiListener(this);
        IJ.addEventListener(this::onIJEvent);

        // IJ2 event subscriptions (command, display, overlay, log)
        eventService.subscribe(this);
    }

    public void unregisterListeners() {
        ImagePlus.removeImageListener(this);
        Roi.removeRoiListener(this);
    }

    // ── IJ2 module events (command tracking) ───────────────────────

    @org.scijava.event.EventHandler
    public void onModuleStarted(
            org.scijava.module.event.ModuleStartedEvent event) {
        moduleStartTimes.put(event.getModule(), System.currentTimeMillis());
    }

    @org.scijava.event.EventHandler
    public void onModuleFinished(
            org.scijava.module.event.ModuleFinishedEvent event) {
        if (!enabledCategories.contains("command")) return;
        org.scijava.module.Module module = event.getModule();
        long start = moduleStartTimes.getOrDefault(module,
                System.currentTimeMillis());
        moduleStartTimes.remove(module);
        long duration = System.currentTimeMillis() - start;

        String command = module.getInfo().getTitle();
        if (command == null || command.isEmpty()) return;

        // Build args string from module inputs
        StringBuilder args = new StringBuilder();
        for (org.scijava.module.ModuleItem<?> input : module.getInfo().inputs()) {
            Object val = module.getInput(input.getName());
            if (val != null && !input.isRequired()) {
                if (args.length() > 0) args.append(" ");
                args.append(input.getName()).append("=").append(val);
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("args", args.toString());
        data.addProperty("duration_ms", duration);
        ImagePlus current = WindowManager.getCurrentImage();
        if (current != null) {
            data.addProperty("image", current.getTitle());
        }
        emit("command", "command_finished", data);
    }

    @org.scijava.event.EventHandler
    public void onModuleError(
            org.scijava.module.event.ModuleCanceledEvent event) {
        if (!enabledCategories.contains("command")) return;
        moduleStartTimes.remove(event.getModule());
        String command = event.getModule().getInfo().getTitle();
        if (command == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("command", command);
        data.addProperty("error", event.getReason());
        emit("command", "command_error", data);
    }

    // ── IJ1 ImageListener ──────────────────────────────────────────

    @Override
    public void imageOpened(ImagePlus imp) {
        if (!enabledCategories.contains("image")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", imp.getTitle());
        data.addProperty("width", imp.getWidth());
        data.addProperty("height", imp.getHeight());
        data.addProperty("depth", imp.getNSlices());
        data.addProperty("channels", imp.getNChannels());
        data.addProperty("frames", imp.getNFrames());
        data.addProperty("type", typeName(imp.getType()));
        if (imp.getOriginalFileInfo() != null
                && imp.getOriginalFileInfo().directory != null) {
            data.addProperty("path",
                    imp.getOriginalFileInfo().directory
                    + imp.getOriginalFileInfo().fileName);
        }
        emit("image", "image_opened", data);
    }

    @Override
    public void imageClosed(ImagePlus imp) {
        if (!enabledCategories.contains("image")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", imp.getTitle());
        emit("image", "image_closed", data);
    }

    @Override
    public void imageUpdated(ImagePlus imp) {
        if (!enabledCategories.contains("image")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", imp.getTitle());
        emit("image", "image_updated", data);
    }

    /**
     * Called by ImageService.saveImage() after saving.
     * IJ1 ImageListener has no save callback.
     */
    public void notifyImageSaved(String title, String path) {
        if (!enabledCategories.contains("image")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", title);
        data.addProperty("path", path);
        emit("image", "image_saved", data);
    }

    // ── IJ1 RoiListener ───────────────────────────────────────────

    @Override
    public void roiModified(ImagePlus imp, int id) {
        if (!enabledCategories.contains("roi")) return;
        String eventName = switch (id) {
            case ij.gui.RoiListener.CREATED   -> "roi_created";
            case ij.gui.RoiListener.MOVED     -> "roi_moved";
            case ij.gui.RoiListener.MODIFIED  -> "roi_modified";
            case ij.gui.RoiListener.COMPLETED -> "roi_completed";
            case ij.gui.RoiListener.DELETED   -> "roi_deleted";
            default -> null;
        };
        if (eventName == null) return;

        JsonObject data = new JsonObject();
        Roi roi = imp.getRoi();
        if (roi != null) {
            data.addProperty("roi_type", roi.getTypeAsString());
            Rectangle bounds = roi.getBounds();
            JsonArray b = new JsonArray();
            b.add(bounds.x); b.add(bounds.y);
            b.add(bounds.width); b.add(bounds.height);
            data.add("bounds", b);
        }
        data.addProperty("image", imp.getTitle());
        emit("roi", eventName, data);
    }

    // ── IJ1 IJEventListener (tool/color changes) ──────────────────

    private void onIJEvent(int eventID) {
        if (!enabledCategories.contains("tool")) return;

        // Detect tool changes by comparing current tool name
        String currentTool = IJ.getToolName();
        if (currentTool != null && !currentTool.equals(lastToolName)) {
            lastToolName = currentTool;
            JsonObject data = new JsonObject();
            data.addProperty("tool_name", currentTool);
            emit("tool", "tool_changed", data);
        }

        // Detect foreground/background color changes
        Color fg = ij.gui.Toolbar.getForegroundColor();
        Color bg = ij.gui.Toolbar.getBackgroundColor();
        if (!fg.equals(lastFgColor)) {
            lastFgColor = fg;
            JsonObject data = new JsonObject();
            data.addProperty("fg_or_bg", "foreground");
            data.addProperty("color", String.format("#%06x", fg.getRGB() & 0xFFFFFF));
            emit("tool", "color_changed", data);
        }
        if (!bg.equals(lastBgColor)) {
            lastBgColor = bg;
            JsonObject data = new JsonObject();
            data.addProperty("fg_or_bg", "background");
            data.addProperty("color", String.format("#%06x", bg.getRGB() & 0xFFFFFF));
            emit("tool", "color_changed", data);
        }
    }

    // ── IJ2 display events ─────────────────────────────────────────

    @org.scijava.event.EventHandler
    public void onDisplayCreated(
            org.scijava.display.event.DisplayCreatedEvent event) {
        if (!enabledCategories.contains("display")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", event.getObject().getName());
        emit("display", "display_created", data);
    }

    @org.scijava.event.EventHandler
    public void onDisplayDeleted(
            org.scijava.display.event.DisplayDeletedEvent event) {
        if (!enabledCategories.contains("display")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", event.getObject().getName());
        emit("display", "display_closed", data);
    }

    @org.scijava.event.EventHandler
    public void onDisplayActivated(
            org.scijava.display.event.DisplayActivatedEvent event) {
        if (!enabledCategories.contains("display")) return;
        JsonObject data = new JsonObject();
        data.addProperty("title", event.getDisplay().getName());
        emit("display", "display_activated", data);
    }

    // ── IJ2 overlay events ─────────────────────────────────────────

    @org.scijava.event.EventHandler
    public void onOverlayUpdated(
            org.scijava.display.event.DisplayUpdatedEvent event) {
        if (!enabledCategories.contains("overlay")) return;
        JsonObject data = new JsonObject();
        data.addProperty("image", event.getDisplay().getName());
        emit("overlay", "overlay_updated", data);
    }

    // ── IJ2 log/output capture ─────────────────────────────────────

    @org.scijava.event.EventHandler
    public void onOutput(org.scijava.console.OutputEvent event) {
        if (!enabledCategories.contains("log")) return;
        String message = event.getOutput();
        if (message == null || message.isBlank()) return;
        JsonObject data = new JsonObject();
        data.addProperty("message", message.strip());
        data.addProperty("level", event.isStderr() ? "error" : "info");
        emit("log", "log_message", data);
    }

    // ── Category management ────────────────────────────────────────

    public JsonObject setCategories(JsonObject params) {
        JsonArray cats = params.getAsJsonArray("categories");
        enabledCategories.clear();
        for (JsonElement cat : cats) {
            enabledCategories.add(cat.getAsString());
        }
        JsonObject result = new JsonObject();
        result.add("enabled_categories", cats);
        return result;
    }

    public JsonArray getEnabledCategoriesJson() {
        JsonArray arr = new JsonArray();
        for (String cat : enabledCategories) {
            arr.add(cat);
        }
        return arr;
    }

    // ── Internal ───────────────────────────────────────────────────

    private void emit(String category, String eventName, JsonObject data) {
        if (eventSink == null) return;
        JsonObject event = new JsonObject();
        event.addProperty("type", "event");
        event.addProperty("category", category);
        event.addProperty("event", eventName);
        event.add("data", data);
        event.addProperty("source", SourceTracker.getSource());
        event.addProperty("timestamp", System.currentTimeMillis() / 1000);
        eventSink.accept(event.toString());
    }

    private String typeName(int type) {
        return switch (type) {
            case ImagePlus.GRAY8 -> "8-bit";
            case ImagePlus.GRAY16 -> "16-bit";
            case ImagePlus.GRAY32 -> "32-bit";
            case ImagePlus.COLOR_256 -> "8-bit color";
            case ImagePlus.COLOR_RGB -> "RGB";
            default -> "unknown";
        };
    }
}
