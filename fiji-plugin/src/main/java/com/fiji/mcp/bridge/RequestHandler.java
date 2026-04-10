package com.fiji.mcp.bridge;

import com.google.gson.JsonObject;

public class RequestHandler {

    private final ScriptExecutor scriptExecutor;
    private final ImageService imageService;
    private final EventEmitter eventEmitter;
    private final ExecutionReporter reporter;
    private final long startTime = System.currentTimeMillis();

    public RequestHandler(
            ScriptExecutor scriptExecutor,
            ImageService imageService,
            EventEmitter eventEmitter,
            ExecutionReporter reporter) {
        this.scriptExecutor = scriptExecutor;
        this.imageService = imageService;
        this.eventEmitter = eventEmitter;
        this.reporter = reporter;
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
            case "wait_for_execution"    -> reporter.waitFor(
                    params.get("execution_id").getAsString(),
                    softTimeoutOf(params));
            case "kill_execution"        -> reporter.kill(
                    params.has("execution_id") && !params.get("execution_id").isJsonNull()
                            ? params.get("execution_id").getAsString() : null);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private static Integer softTimeoutOf(JsonObject params) {
        if (!params.has("soft_timeout_seconds")) return null;
        if (params.get("soft_timeout_seconds").isJsonNull()) return null;
        return params.get("soft_timeout_seconds").getAsInt();
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
