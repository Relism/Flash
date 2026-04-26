package dev.relism.flash.ext.webbundler;

import java.util.List;

final class ViteFrontendStrategy implements FrontendStrategy {
    @Override
    public FrontendType type() {
        return FrontendType.VITE;
    }

    @Override
    public List<String> devCommand(WebBundlerConfig config, PackageManagerAdapter adapter) {
        if (config.devCommand() != null) {
            return CommandTokens.split(config.devCommand());
        }
        return adapter.devCommand(config.devHost(), config.devPort());
    }

    @Override
    public List<String> buildCommand(WebBundlerConfig config, PackageManagerAdapter adapter) {
        if (config.buildCommand() != null) {
            return CommandTokens.split(config.buildCommand());
        }
        return adapter.buildCommand();
    }
}
