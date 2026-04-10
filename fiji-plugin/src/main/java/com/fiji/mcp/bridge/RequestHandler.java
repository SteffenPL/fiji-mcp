package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class RequestHandler {

    private final ScriptExecutor scriptExecutor;
    private final ImageService imageService;
    private final EventEmitter eventEmitter;
    private final long startTime = System.currentTimeMillis();

    public RequestHandler(
            ScriptExecutor scriptExecutor,
            ImageService imageService,
            EventEmitter eventEmitter) {
        this.scriptExecutor = scriptExecutor;
        this.imageService = imageService;
        this.eventEmitter = eventEmitter;
    }

    public JsonObject handle(String action, JsonObject params) {
        return switch (action) {
            case "run_ij_macro"          -> scriptExecutor.runMacro(params);
            case "run_script"            -> scriptExecutor.runScript(params);
            case "run_command"           -> scriptExecutor.runCommand(params);
            case "list_commands"         -> scriptExecutor.listCommands(params);
            case "list_images"           -> imageService.listImages();
            case "get_image_info"        -> imageService.getImageInfo(params);
            case "save_image"            -> imageService.saveImage(params);
            case "get_results_table"     -> imageService.getResultsTable(params);
            case "get_log"               -> imageService.getLog(params);
            case "status"                -> buildStatus();
            case "set_event_categories"  -> eventEmitter.setCategories(params);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonObject buildStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("connected", true);
        result.addProperty("fiji_version", ij.IJ.getVersion());
        result.add("enabled_categories", eventEmitter.getEnabledCategoriesJson());
        result.addProperty("uptime_s",
                (System.currentTimeMillis() - startTime) / 1000);
        return result;
    }
}
