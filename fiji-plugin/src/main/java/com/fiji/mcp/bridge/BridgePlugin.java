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

        // Wire up components
        EventEmitter emitter = new EventEmitter();
        ExecutionReporter reporter = new ExecutionReporter(
                ij.IJ::getLog,
                () -> {
                    ij.ImagePlus imp = ij.WindowManager.getCurrentImage();
                    return imp == null ? null : imp.getTitle();
                });
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
