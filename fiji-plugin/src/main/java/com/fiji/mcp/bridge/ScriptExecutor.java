package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.Menus;
import ij.WindowManager;
import org.scijava.script.ScriptService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ScriptExecutor {

    private final ScriptService scriptService;

    public ScriptExecutor(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    public JsonObject runMacro(JsonObject params) {
        String code = params.get("code").getAsString();
        String args = params.has("args") ? params.get("args").getAsString() : "";
        SourceTracker.setMcpActive(true);
        try {
            String output = IJ.runMacro(code, args);
            return buildScriptResult(output);
        } finally {
            SourceTracker.setMcpActive(false);
        }
    }

    public JsonObject runScript(JsonObject params) {
        String language = params.get("language").getAsString();
        String code = params.get("code").getAsString();
        SourceTracker.setMcpActive(true);
        try {
            String ext = extensionFor(language);
            Path tempScript = Files.createTempFile("fiji_mcp_", "." + ext);
            Files.writeString(tempScript, code);
            Future<org.scijava.script.ScriptModule> future =
                    scriptService.run(tempScript.toFile(), false, (Map<String, Object>) null);
            org.scijava.script.ScriptModule module = future.get(60, TimeUnit.SECONDS);
            StringBuilder output = new StringBuilder();
            for (org.scijava.module.ModuleItem<?> item : module.getInfo().outputs()) {
                Object val = module.getOutput(item.getName());
                if (val != null) output.append(val);
            }
            Files.deleteIfExists(tempScript);
            return buildScriptResult(output.toString());
        } catch (Exception e) {
            throw new RuntimeException("Script error: " + e.getMessage(), e);
        } finally {
            SourceTracker.setMcpActive(false);
        }
    }

    public JsonObject runCommand(JsonObject params) {
        String command = params.get("command").getAsString();
        String args = params.has("args") ? params.get("args").getAsString() : "";
        SourceTracker.setMcpActive(true);
        try {
            IJ.run(command, args);
            return buildScriptResult("");
        } finally {
            SourceTracker.setMcpActive(false);
        }
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

    private JsonObject buildScriptResult(String output) {
        JsonObject result = new JsonObject();
        result.addProperty("output", output != null ? output : "");
        if (WindowManager.getCurrentImage() != null) {
            result.addProperty("active_image",
                    WindowManager.getCurrentImage().getTitle());
        }
        return result;
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
