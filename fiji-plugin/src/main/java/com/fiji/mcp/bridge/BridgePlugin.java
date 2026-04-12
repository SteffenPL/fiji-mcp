package com.fiji.mcp.bridge;

import ij.IJ;
import org.scijava.command.Command;
import org.scijava.event.EventService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptService;

@Plugin(type = Command.class, menuPath = "Plugins>fiji-mcp>Start Bridge")
public class BridgePlugin implements Command {

    @Parameter
    private EventService eventService;

    @Parameter
    private ScriptService scriptService;

    private static BridgeWebSocketServer server;

    @Override
    public void run() {
        if (server != null) {
            IJ.log("[fiji-mcp] Bridge is already running on port "
                    + server.getPort());
            return;
        }

        int port = Integer.parseInt(
                System.getenv().getOrDefault("FIJI_MCP_PORT", "8765"));

        // Install a stderr tee so per-execution stderr gets captured into the
        // envelope instead of being swallowed by the terminal — see fm-50jy.
        StderrTeeStream stderrTee =
                new StderrTeeStream(System.err);
        System.setErr(stderrTee);

        // Route ImageJ exception traces through System.err so the tee above
        // catches them. The default handler in GUI mode opens a TextWindow
        // popup and writes nothing to any stream, leaving the trace invisible
        // to envelope.stderr — see fp-89zk.
        IJ.setExceptionHandler(new BridgeExceptionHandler());

        // Watchdog scheduler — single daemon thread for dialog polling.
        java.util.concurrent.ScheduledExecutorService watchdogScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "fiji-mcp-watchdog");
                    t.setDaemon(true);
                    return t;
                });

        // Execution lock — phase 1: construct without cancel hook.
        ExecutionLock lock = new ExecutionLock(IJ.getInstance());

        // Wire up components
        EventEmitter emitter = new EventEmitter();
        java.util.function.Supplier<DialogWatchdog> watchdogFactory =
                () -> new DialogWatchdog(
                        AwtDialogProbe::currentAll, watchdogScheduler, 500, 20);

        ExecutionReporter reporter = new ExecutionReporter(
                ij.IJ::getLog,
                () -> {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    return imp == null ? null : imp.getTitle();
                },
                stderrTee,
                watchdogFactory,
                lock::acquire,
                lock::release);

        // Phase 2: now that reporter exists, wire its kill into the lock's
        // Cancel button.
        lock.setCancelHook(() -> reporter.kill(null));
        ScriptExecutor executor = new ScriptExecutor(scriptService, reporter);
        ImageService imageService = new ImageService();
        imageService.setEventEmitter(emitter);
        RequestHandler handler = new RequestHandler(
                executor, imageService, emitter, reporter);

        server = new BridgeWebSocketServer(port, handler);
        emitter.setEventSink(server::broadcastEvent);
        emitter.registerListeners(eventService);

        server.start();
        IJ.log("[fiji-mcp] Bridge started on port " + port);
    }
}
