package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.Menus;
import ij.macro.Interpreter;
import org.scijava.script.ScriptService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        // Direct Interpreter invocation bypasses ij.plugin.Macro_Runner, whose
        // catch block calls IJ.handleException (modal dialog) and returns the
        // sentinel "[aborted]".
        //
        // setIgnoreErrors(true) is a deliberate trade-off. Without it,
        // Interpreter.error(String) calls its own package-private showError(),
        // which constructs a modal GenericDialog that blocks the worker
        // thread until a user dismisses it. There is no clean public API to
        // suppress that dialog (IJ.redirectErrorMessages only affects
        // IJ.error() call sites, not Interpreter's own showError). With the
        // flag set, Interpreter.error() stores errorMessage and returns
        // silently — we then promote the silent error into a throw via the
        // post-run check in runInterpreter.
        //
        // KNOWN QUIRK: setIgnoreErrors(true) also makes the interpreter
        // continue past parse errors with undefined identifiers coerced to 0.
        // For an expression statement like `foo();`, the IJ macro interpreter
        // auto-prints the top-level value, which leaks "0\n" into stdout
        // before the error is detected. Cosmetic; the error envelope itself
        // is correct.
        //
        // The cancel hook gives ExecutionReporter a real cross-thread abort
        // primitive — Interpreter.abortMacro() — in place of the no-op
        // ij.Macro.abort() that used to live in internalCancel.
        Interpreter interp = new Interpreter();
        interp.setIgnoreErrors(true);
        return reporter.runReported("macro",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> runInterpreter(interp, code, args),
                interp::abortMacro);
    }

    public JsonObject runScript(JsonObject params) {
        String language = params.get("language").getAsString();
        String code = params.get("code").getAsString();
        // Capture the inner SciJava Future so the cancel hook can stop it too.
        // Without this, killing a running script only interrupts the worker
        // thread waiting in future.get() — the SciJava engine keeps running.
        AtomicReference<Future<?>> innerRef = new AtomicReference<>();
        Runnable hook = () -> {
            Future<?> f = innerRef.get();
            if (f != null) f.cancel(true);
        };
        return reporter.runReported("script",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> executeScijavaScript(language, code, innerRef),
                hook);
    }

    public JsonObject runCommand(JsonObject params) {
        String command = params.get("command").getAsString();
        String args = params.has("args") ? params.get("args").getAsString() : "";
        // No clean cross-thread abort primitive for IJ.run(command, args).
        return reporter.runReported("command",
                softTimeoutOf(params), hardTimeoutOf(params),
                () -> {
                    IJ.run(command, args);
                    return null;
                },
                null);
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

    private Object executeScijavaScript(String language, String code,
                                        AtomicReference<Future<?>> innerRef) throws Exception {
        String ext = extensionFor(language);
        Path tempScript = Files.createTempFile("fiji_mcp_", "." + ext);
        try {
            Files.writeString(tempScript, code);
            Future<org.scijava.script.ScriptModule> future =
                    scriptService.run(tempScript.toFile(), false, (Map<String, Object>) null);
            innerRef.set(future);
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

    private static Object runInterpreter(Interpreter interp, String code, String args) {
        Object result;
        try {
            result = interp.run(code, args);
        } catch (Throwable t) {
            // Error raised from interp.run(). Replicates Macro_Runner.runMacro's
            // catch body MINUS IJ.handleException (modal dialog) and MINUS the
            // "[aborted]" sentinel return.
            throw wrapMacroError(interp, t);
        }
        // With setIgnoreErrors(true), Interpreter.error(String) silently
        // returns without throwing on parse errors. Promote any stored
        // errorMessage into a thrown exception so the reporter builds an
        // error envelope instead of reporting success.
        String interpMsg = interp.getErrorMessage();
        if (interpMsg != null && !interpMsg.isEmpty()) {
            throw wrapMacroError(interp, null);
        }
        return result;
    }

    private static RuntimeException wrapMacroError(Interpreter interp, Throwable cause) {
        String interpMsg = interp.getErrorMessage();
        int line = interp.getLineNumber();
        String base = (interpMsg != null && !interpMsg.isEmpty())
                ? interpMsg
                : (cause != null && cause.getMessage() != null ? cause.getMessage()
                   : cause != null ? cause.getClass().getSimpleName()
                                   : "macro error");
        String formatted = (line > 0 && !base.contains("in line "))
                ? base + " in line " + line
                : base;
        return cause != null ? new RuntimeException(formatted, cause)
                             : new RuntimeException(formatted);
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
