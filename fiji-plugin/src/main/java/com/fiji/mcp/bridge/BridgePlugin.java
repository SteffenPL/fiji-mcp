package com.fiji.mcp.bridge;

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

    @Override
    public void run() {
        BridgeBootstrap.start(eventService, scriptService);
    }
}
