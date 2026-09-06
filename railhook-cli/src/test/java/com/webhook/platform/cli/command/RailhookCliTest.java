package com.webhook.platform.cli.command;

import com.webhook.platform.cli.RailhookCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class RailhookCliTest {

    @Test
    void shouldShowHelpWithHelpFlag() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--help");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("railhook"));
        assertTrue(output.contains("login"));
        assertTrue(output.contains("listen"));
        assertTrue(output.contains("status"));
        assertTrue(output.contains("replay"));
        assertTrue(output.contains("tunnels"));
    }

    @Test
    void shouldShowVersion() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--version");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("1.0.0"));
    }

    @Test
    void shouldShowLoginHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("login", "--help");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Authenticate"));
    }

    @Test
    void shouldShowListenHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("listen", "--help");

        assertEquals(0, exitCode);
        String output = out.toString();
        assertTrue(output.contains("tunnel"));
        assertTrue(output.contains("port"));
    }

    @Test
    void shouldShowReplayHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("replay", "--help");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("Replay"));
    }

    @Test
    void shouldShowTunnelsHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("tunnels", "--help");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("tunnel"));
    }

    @Test
    void shouldShowConfigHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("config", "--help");

        assertEquals(0, exitCode);
        assertTrue(out.toString().contains("configuration"));
    }

    @Test
    void shouldRejectUnknownSubcommand() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new RailhookCli());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("nonexistent");

        assertNotEquals(0, exitCode);
    }
}
