package dev.relism.ext.webbundler;

import java.util.List;

final class PackageManagerAdapter {
    private final WebBundlerConfig config;
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");

    PackageManagerAdapter(WebBundlerConfig config) {
        this.config = config;
    }

    List<String> installCommand() {
        if (config.installCommand() != null) return CommandTokens.split(config.installCommand());
        return switch (config.packageManager()) {
            case NPM -> List.of(bin("npm"), "install");
            case PNPM -> List.of(bin("pnpm"), "install", "--frozen-lockfile");
            case YARN -> List.of(bin("yarn"), "install", "--frozen-lockfile");
            case BUN -> List.of(bin("bun"), "install", "--frozen-lockfile");
        };
    }

    List<String> devCommand(String host, int port) {
        return switch (config.packageManager()) {
            case NPM -> List.of(bin("npm"), "run", "dev", "--", "--host", host, "--port", String.valueOf(port));
            case PNPM -> List.of(bin("pnpm"), "dev", "--host", host, "--port", String.valueOf(port));
            case YARN -> List.of(bin("yarn"), "dev", "--host", host, "--port", String.valueOf(port));
            case BUN -> List.of(bin("bun"), "run", "dev", "--host", host, "--port", String.valueOf(port));
        };
    }

    List<String> buildCommand() {
        return switch (config.packageManager()) {
            case NPM -> List.of(bin("npm"), "run", "build");
            case PNPM -> List.of(bin("pnpm"), "build");
            case YARN -> List.of(bin("yarn"), "build");
            case BUN -> List.of(bin("bun"), "run", "build");
        };
    }

    private String bin(String name) {
        return windows ? name + ".cmd" : name;
    }
}
