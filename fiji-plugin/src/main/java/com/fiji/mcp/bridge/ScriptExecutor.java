package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.Menus;
import org.scijava.script.ScriptService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ScriptExecutor {

    private static final int DEFAULT_HARD_TIMEOUT_SECONDS = 600;

    private final ScriptService scriptService;
    private final ExecutionReporter reporter;

    public ScriptExecutor(ScriptService scriptService, ExecutionReporter reporter) {
        this.scriptService = scriptService;
        this.reporter = reporter;
    }

    public JsonObject runMacro(JsonObject params) {
        String code = params.get("code").getAsString();
        String args = params.has("args") ? params.get("args").getAsString() : "";
        return reporter.runReported("macro",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> IJ.runMacro(code, args));
    }

    public JsonObject runScript(JsonObject params) {
        String language = params.get("language").getAsString();
        String code = params.get("code").getAsString();
        return reporter.runReported("script",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> executeScijavaScript(language, code));
    }

    public JsonObject runCommand(JsonObject params) {
        String command = params.get("command").getAsString();
        String args = params.has("args") ? params.get("args").getAsString() : "";
        return reporter.runReported("command",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> {
                    IJ.run(command, args);
                    return null;
                });
    }

    public JsonObject listCommands(JsonObject params) {
        String pattern = params.get("pattern").getAsString().toLowerCase();
        Hashtable<String, String> commands = Menus.getCommands();
        JsonArray matches = new JsonArray();
        int count = 0;
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            if (count >= 100) break;
            if (entry.getKey().toLowerCase().contains(pattern)) {
                JsonObject cmd = new JsonObject();
                cmd.addProperty("name", entry.getKey());
                cmd.addProperty("class", entry.getValue());
                matches.add(cmd);
                count++;
            }
        }
        JsonObject result = new JsonObject();
        result.add("commands", matches);
        result.addProperty("count", count);
        return result;
    }

    // ── private helpers ───────────────────────────────────────────────

    private Integer softTimeoutOf(JsonObject params) {
        if (!params.has("soft_timeout_seconds")) return null;
        if (params.get("soft_timeout_seconds").isJsonNull()) return null;
        return params.get("soft_timeout_seconds").getAsInt();
    }

    private int hardTimeoutOf(JsonObject params) {
        return params.has("hard_timeout_seconds")
                ? params.get("hard_timeout_seconds").getAsInt()
                : DEFAULT_HARD_TIMEOUT_SECONDS;
    }

    private Object executeScijavaScript(String language, String code) throws Exception {
        String ext = extensionFor(language);
        Path tempScript = Files.createTempFile("fiji_mcp_", "." + ext);
        try {
            Files.writeString(tempScript, code);
            Future<org.scijava.script.ScriptModule> future =
                    scriptService.run(tempScript.toFile(), false, (Map<String, Object>) null);
            // Generous internal timeout — the outer hard timeout in ExecutionReporter
            // is the user-facing one. This is just a backstop against truly stuck
            // script engines that ignore interrupts.
            org.scijava.script.ScriptModule module = future.get(3600, TimeUnit.SECONDS);
            StringBuilder output = new StringBuilder();
            for (org.scijava.module.ModuleItem<?> item : module.getInfo().outputs()) {
                Object val = module.getOutput(item.getName());
                if (val != null) output.append(val);
            }
            return output.length() == 0 ? null : output.toString();
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }

    private String extensionFor(String language) {
        return switch (language.toLowerCase()) {
            case "python", "jython" -> "py";
            case "groovy" -> "groovy";
            case "javascript", "js" -> "js";
            case "beanshell", "bsh" -> "bsh";
            case "clojure" -> "clj";
            case "scala" -> "scala";
            case "ijm", "macro" -> "ijm";
            default -> language;
        };
    }
}
