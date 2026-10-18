package com.deployguard;

import com.deployguard.agent.AgentMain;
import com.deployguard.controller.ControllerMain;

// One jar, two process roles: `java -jar deployguard.jar controller` or `... agent`.
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        // All timestamps are instants; UTC keeps log and event times the same on every machine.
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String role = args.length > 0 ? args[0] : System.getenv().getOrDefault("DG_ROLE", "");
        switch (role) {
            case "controller" -> ControllerMain.main(args);
            case "agent" -> AgentMain.main(args);
            default -> {
                System.err.println("Usage: java -jar deployguard.jar <controller|agent>  (or set DG_ROLE)");
                System.exit(2);
            }
        }
    }
}
