package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "config",
        description = "View or update CLI configuration",
        mixinStandardHelpOptions = true,
        subcommands = {
                ConfigCommand.ShowSubcommand.class,
                ConfigCommand.SetSubcommand.class,
                ConfigCommand.ClearSubcommand.class,
                ConfigCommand.ProfileCommand.class
        }
)
public class ConfigCommand implements Callable<Integer> {

    @Override
    public Integer call() throws Exception {
        return new ShowSubcommand().call();
    }

    @Command(name = "show", description = "Show current configuration", mixinStandardHelpOptions = true)
    public static class ShowSubcommand implements Callable<Integer> {

        private final PrintStream out = System.out;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            CliConfig config = configService.load();

            out.println("Railhook CLI Configuration");
            out.println("══════════════════════════════════════");
            out.println("  File:        " + configService.getConfigPath());
            out.println("  Backend URL: " + config.getBackendUrl());
            out.println("  Auth:        " + (config.isAuthenticated() ? "✓ authenticated" : "✗ not authenticated"));
            if (config.getAccessToken() != null) {
                out.println("  Token:       " + config.getAccessToken().substring(0, Math.min(20, config.getAccessToken().length())) + "…");
            }
            if (config.getUserId() != null) out.println("  User ID:     " + config.getUserId());
            if (config.getOrganizationId() != null) out.println("  Org ID:      " + config.getOrganizationId());
            if (config.getActiveProjectId() != null) out.println("  Project ID:  " + config.getActiveProjectId());
            String profile = config.getActiveProfile() != null ? config.getActiveProfile() : "default";
            out.println("  Profile:     " + profile);
            if (config.getProfiles() != null && !config.getProfiles().isEmpty()) {
                out.println("  Profiles:    " + String.join(", ", config.getProfiles().keySet()));
            }
            return 0;
        }
    }

    @Command(name = "set", description = "Set a configuration value", mixinStandardHelpOptions = true)
    public static class SetSubcommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Key to set (backend-url, project-id)")
        private String key;

        @Parameters(index = "1", description = "Value to set")
        private String value;

        private final PrintStream out = System.out;
        private final PrintStream err = System.err;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            CliConfig config = configService.load();

            switch (key) {
                case "backend-url" -> config.setBackendUrl(value);
                case "project-id" -> config.setActiveProjectId(value);
                default -> {
                    err.println("✗ Unknown config key: " + key);
                    err.println("  Available keys: backend-url, project-id");
                    return 1;
                }
            }

            configService.save(config);
            out.println("✓ Set " + key + " = " + value);
            return 0;
        }
    }

    @Command(name = "clear", description = "Clear configuration and logout", mixinStandardHelpOptions = true)
    public static class ClearSubcommand implements Callable<Integer> {

        private final PrintStream out = System.out;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            configService.clear();
            out.println("✓ Configuration cleared");
            return 0;
        }
    }

    @Command(
            name = "profile",
            description = "Manage configuration profiles (staging, production, etc.)",
            mixinStandardHelpOptions = true,
            subcommands = {
                    ProfileCommand.ListProfiles.class,
                    ProfileCommand.UseProfile.class,
                    ProfileCommand.CreateProfile.class,
                    ProfileCommand.DeleteProfile.class
            }
    )
    public static class ProfileCommand implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            return new ListProfiles().call();
        }

        @Command(name = "list", description = "List all profiles", mixinStandardHelpOptions = true)
        public static class ListProfiles implements Callable<Integer> {

            private final PrintStream out = System.out;

            @Override
            public Integer call() throws Exception {
                CliConfigService configService = new CliConfigService();
                CliConfig config = configService.load();
                String active = config.getActiveProfile() != null ? config.getActiveProfile() : "default";

                out.println("Profiles:");
                out.println("─────────────────────────────────────────");

                // Always show "default" (the root-level config)
                out.printf("  %s default  → %s%n",
                        active.equals("default") ? "*" : " ",
                        config.getBackendUrl());

                Map<String, CliConfig.ProfileConfig> profiles = config.getProfiles();
                if (profiles != null) {
                    for (var entry : profiles.entrySet()) {
                        String name = entry.getKey();
                        if (name.equals("default")) continue;
                        String url = entry.getValue().getBackendUrl() != null
                                ? entry.getValue().getBackendUrl() : "(not set)";
                        out.printf("  %s %-8s → %s%n",
                                active.equals(name) ? "*" : " ",
                                name, url);
                    }
                }
                return 0;
            }
        }

        @Command(name = "use", description = "Switch to a profile", mixinStandardHelpOptions = true)
        public static class UseProfile implements Callable<Integer> {

            @Parameters(index = "0", description = "Profile name to switch to")
            private String name;

            private final PrintStream out = System.out;
            private final PrintStream err = System.err;

            @Override
            public Integer call() throws Exception {
                CliConfigService configService = new CliConfigService();
                CliConfig config = configService.load();

                // Save current state into current profile before switching
                String currentProfile = config.getActiveProfile() != null ? config.getActiveProfile() : "default";
                config.ensureProfiles().put(currentProfile, config.toProfile());

                if (name.equals("default")) {
                    // Switch back to default — apply from stored default profile if it exists
                    CliConfig.ProfileConfig defaultProfile = config.ensureProfiles().get("default");
                    if (defaultProfile != null) {
                        config.applyProfile(defaultProfile);
                    }
                    config.setActiveProfile(null);
                } else {
                    CliConfig.ProfileConfig target = config.ensureProfiles().get(name);
                    if (target == null) {
                        err.println("✗ Profile '" + name + "' not found. Create it first: railhook config profile create " + name);
                        return 1;
                    }
                    config.applyProfile(target);
                    config.setActiveProfile(name);
                }

                configService.save(config);
                out.println("✓ Switched to profile: " + name);
                out.println("  Backend URL: " + config.getBackendUrl());
                out.println("  Auth:        " + (config.isAuthenticated() ? "✓ authenticated" : "✗ not authenticated"));
                return 0;
            }
        }

        @Command(name = "create", description = "Create a new profile", mixinStandardHelpOptions = true)
        public static class CreateProfile implements Callable<Integer> {

            @Parameters(index = "0", description = "Profile name")
            private String name;

            @Option(names = {"--url"}, description = "Backend URL for this profile")
            private String url;

            private final PrintStream out = System.out;
            private final PrintStream err = System.err;

            @Override
            public Integer call() throws Exception {
                CliConfigService configService = new CliConfigService();
                CliConfig config = configService.load();

                if (config.ensureProfiles().containsKey(name)) {
                    err.println("✗ Profile '" + name + "' already exists");
                    return 1;
                }

                CliConfig.ProfileConfig profile = new CliConfig.ProfileConfig();
                profile.setBackendUrl(url != null ? url : "http://localhost:8080");
                config.ensureProfiles().put(name, profile);
                configService.save(config);

                out.println("✓ Profile '" + name + "' created");
                out.println("  Backend URL: " + profile.getBackendUrl());
                out.println("  Switch to it: railhook config profile use " + name);
                out.println("  Then login:   railhook login");
                return 0;
            }
        }

        @Command(name = "delete", description = "Delete a profile", mixinStandardHelpOptions = true)
        public static class DeleteProfile implements Callable<Integer> {

            @Parameters(index = "0", description = "Profile name to delete")
            private String name;

            private final PrintStream out = System.out;
            private final PrintStream err = System.err;

            @Override
            public Integer call() throws Exception {
                if (name.equals("default")) {
                    err.println("✗ Cannot delete the default profile");
                    return 1;
                }

                CliConfigService configService = new CliConfigService();
                CliConfig config = configService.load();

                if (config.getProfiles() == null || !config.getProfiles().containsKey(name)) {
                    err.println("✗ Profile '" + name + "' not found");
                    return 1;
                }

                // If deleting the active profile, switch back to default
                if (name.equals(config.getActiveProfile())) {
                    CliConfig.ProfileConfig defaultProfile = config.getProfiles().get("default");
                    if (defaultProfile != null) {
                        config.applyProfile(defaultProfile);
                    }
                    config.setActiveProfile(null);
                }

                config.getProfiles().remove(name);
                configService.save(config);
                out.println("✓ Profile '" + name + "' deleted");
                return 0;
            }
        }
    }
}
