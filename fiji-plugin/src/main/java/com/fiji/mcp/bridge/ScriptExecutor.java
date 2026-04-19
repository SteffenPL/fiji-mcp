package com.fiji.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ij.IJ;
import ij.Menus;
import ij.macro.Interpreter;
import org.scijava.script.ScriptService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    // TLD-style leading segments: for these, group by first 3 segments
    // (e.g. "sc.fiji.trackmate"); for everything else, group by first 2
    // (e.g. "inra.ijpb"). Matches the convention in the feedback ticket.
    private static final Set<String> TLD_LIKE_ROOTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "sc", "de", "net", "com", "org", "edu", "io", "gov",
                    "uk", "fr", "jp", "at", "it", "ch", "us", "ca", "nl", "au")));

    public JsonObject listPluginPackages() {
        Hashtable<String, String> commands = Menus.getCommands();
        Map<String, List<String>> byPrefix = new TreeMap<>();
        for (Map.Entry<String, String> entry : commands.entrySet()) {
            String prefix = packagePrefix(entry.getValue());
            if (prefix == null) continue;
            List<String> bucket = byPrefix.get(prefix);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byPrefix.put(prefix, bucket);
            }
            bucket.add(entry.getKey());
        }

        List<Map.Entry<String, List<String>>> sorted =
                new ArrayList<>(byPrefix.entrySet());
        sorted.sort(new Comparator<Map.Entry<String, List<String>>>() {
            @Override
            public int compare(Map.Entry<String, List<String>> a,
                               Map.Entry<String, List<String>> b) {
                int cmp = Integer.compare(b.getValue().size(), a.getValue().size());
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            }
        });

        JsonArray packages = new JsonArray();
        for (Map.Entry<String, List<String>> e : sorted) {
            JsonObject pkg = new JsonObject();
            pkg.addProperty("prefix", e.getKey());
            pkg.addProperty("command_count", e.getValue().size());
            List<String> examples = new ArrayList<>(e.getValue());
            Collections.sort(examples);
            JsonArray exArr = new JsonArray();
            for (int i = 0; i < Math.min(3, examples.size()); i++) {
                exArr.add(examples.get(i));
            }
            pkg.add("example_commands", exArr);
            packages.add(pkg);
        }

        JsonObject result = new JsonObject();
        result.add("packages", packages);
        return result;
    }

    // Extract a package prefix from a Menus.getCommands() class reference.
    // Values may include constructor args, e.g. 'ij.plugin.Thresholder("mean")';
    // strip them before splitting. Returns null for unrecognizable shapes.
    static String packagePrefix(String classRef) {
        if (classRef == null) return null;
        String s = classRef.trim();
        if (s.isEmpty()) return null;
        int paren = s.indexOf('(');
        if (paren >= 0) s = s.substring(0, paren).trim();
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        if (parts.length < 2) return null;
        int segments = TLD_LIKE_ROOTS.contains(parts[0]) ? 3 : 2;
        // Never include the final segment (the class name itself).
        if (segments > parts.length - 1) segments = parts.length - 1;
        if (segments < 1) return null;
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < segments; i++) {
            out.append('.').append(parts[i]);
        }
        return out.toString();
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
            Files.write(tempScript, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        String lang = language.toLowerCase();
        if ("python".equals(lang) || "jython".equals(lang)) return "py";
        if ("groovy".equals(lang)) return "groovy";
        if ("javascript".equals(lang) || "js".equals(lang)) return "js";
        if ("beanshell".equals(lang) || "bsh".equals(lang)) return "bsh";
        if ("clojure".equals(lang)) return "clj";
        if ("scala".equals(lang)) return "scala";
        if ("ijm".equals(lang) || "macro".equals(lang)) return "ijm";
        return language;
    }
}
