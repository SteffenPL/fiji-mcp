---
id: fm-meeg
status: open
deps: []
links: [fm-kflp, fm-y2ax]
created: 2026-04-13T00:52:47Z
type: bug
priority: 3
assignee: SteffenPL
tags: [bridge, infrastructure, investigation]
---
# SciJava Service plugins from plugins/ dir are not instantiated by Fiji

During auto-start implementation (fm-y2ax), a @Plugin(type = Service.class) extending AbstractService was never instantiated by Fiji despite correct META-INF/json metadata. The class static initializer never fired, confirming the class was never loaded.

## What was tried
1. @EventHandler on UIShownEvent — race condition: event fires before service's handlers are subscribed
2. Override initialize() with SwingUtilities.invokeLater — initialize() never called (class never loaded)
3. Override initialize() with polling daemon thread — same: never called

## What works
- @Plugin(type = Command.class) from the same JAR IS discovered and appears in the menu
- The annotation processor correctly writes both the Command and Service entries to META-INF/json/org.scijava.plugin.Plugin

## Hypothesis
Fiji's classloader or ServiceHelper may not eagerly instantiate Service plugins from JARs in the plugins/ directory the same way it handles Command plugins. Possibly related to the absence of a custom Service interface (we extended AbstractService directly without defining a FooService interface). Or the shaded JAR structure may interfere with service discovery specifically.

## Impact
Low — the -eval workaround is simpler anyway. But this blocks using Service-based hooks for headless mode (fm-kflp) if that approach is needed later. Worth investigating when headless mode work begins.

