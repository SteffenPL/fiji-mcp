package com.fiji.mcp.bridge;

import ij.IJ;
import org.scijava.event.EventHandler;
import org.scijava.event.EventService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.script.ScriptService;
import org.scijava.service.AbstractService;
import org.scijava.service.Service;
import org.scijava.ui.event.UIShownEvent;

/**
 * Auto-starts the MCP bridge when Fiji boots, if opted in via
 * the {@code FIJI_MCP_AUTOSTART=1} environment variable.
 *
 * <p>Uses {@link UIShownEvent} so the IJ1 legacy layer and Swing UI
 * are fully initialized before the bridge wires up components that
 * depend on {@code IJ.getInstance()} and {@code WindowManager}.
 */
@Plugin(type = Service.class)
public class BridgeAutoStart extends AbstractService {

    @Parameter
    private EventService eventService;

    @Parameter
    private ScriptService scriptService;

    @EventHandler
    protected void onUIShown(final UIShownEvent evt) {
        if (!"1".equals(System.getenv("FIJI_MCP_AUTOSTART"))) {
            return;
        }
        IJ.log("[fiji-mcp] Auto-start triggered (FIJI_MCP_AUTOSTART=1)");
        BridgeBootstrap.start(eventService, scriptService);
    }
}
