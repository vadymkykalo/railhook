package com.webhook.platform.cli.command;

import com.webhook.platform.cli.RailhookCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class RailhookCliTest {

    @Test
    void everySubcommandIsRegistered() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        String output = out.toString();
        for (String subcommand : new String[]{"login", "listen", "status", "replay", "tunnels", "config", "events", "admin"}) {
            assertTrue(output.contains(subcommand), subcommand);
        }
    }
}
