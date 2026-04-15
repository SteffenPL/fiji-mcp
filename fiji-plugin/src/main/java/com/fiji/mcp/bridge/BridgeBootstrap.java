package com.fiji.mcp.bridge;

import ij.IJ;
import org.scijava.event.EventService;
import org.scijava.script.ScriptService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

public class BridgeBootstrap {

    private static BridgeWebSocketServer server;

    /**
     * Start the bridge if not already running. Idempotent and thread-safe.
     *
     * @return the running server, or null if the bridge was already started.
     */
    public static synchronized BridgeWebSocketServer start(
            EventService eventService,
            ScriptService scriptService) {
        if (server != null) {
            IJ.log("[fiji-mcp] Bridge is already running on port "
                    + server.getPort());
            return null;
        }

        int port = Integer.parseInt(
                System.getenv().getOrDefault("FIJI_MCP_PORT", "8765"));

        StderrTeeStream stderrTee = new StderrTeeStream(System.err);
        System.setErr(stderrTee);

        IJ.setExceptionHandler(new BridgeExceptionHandler());

        ScheduledExecutorService watchdogScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "fiji-mcp-watchdog");
                    t.setDaemon(true);
                    return t;
                });

        ExecutionLock lock = new ExecutionLock(IJ.getInstance());

        EventEmitter emitter = new EventEmitter();
        Supplier<DialogWatchdog> watchdogFactory =
                () -> new DialogWatchdog(
                        AwtDialogProbe::currentAll, watchdogScheduler, 500, 20);

        ExecutionReporter reporter = new ExecutionReporter(
                IJ::getLog,
                () -> {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    return imp == null ? null : imp.getTitle();
                },
                stderrTee,
                watchdogFactory,
                lock::acquire,
                lock::release,
                ImageService::resultsTableRowCount,
                startRow -> ImageService.snapshotResultsTable(20, 8, startRow));

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
        return server;
    }

    /** Check whether the bridge is currently running. */
    public static boolean isRunning() {
        return server != null;
    }
}
