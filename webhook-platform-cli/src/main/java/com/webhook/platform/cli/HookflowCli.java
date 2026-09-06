package com.webhook.platform.cli;

import com.webhook.platform.cli.command.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "hookflow",
        description = "Hookflow CLI — local webhook tunnel, event replay, and diagnostics",
        version = "hookflow 1.0.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                LoginCommand.class,
                ListenCommand.class,
                StatusCommand.class,
                ReplayCommand.class,
                TunnelsCommand.class,
                EventsTailCommand.class,
                ConfigCommand.class,
                AdminCommand.class
        }
)
public class HookflowCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HookflowCli())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println(cmd.getColorScheme().errorText("Error: " + ex.getMessage()));
                    if (System.getenv("HOOKFLOW_DEBUG") != null) {
                        ex.printStackTrace(cmd.getErr());
                    }
                    return 1;
                })
                .execute(args);
        System.exit(exitCode);
    }
}
