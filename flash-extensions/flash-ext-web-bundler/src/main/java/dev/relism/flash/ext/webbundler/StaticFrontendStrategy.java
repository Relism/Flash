package dev.relism.flash.ext.webbundler;

import java.util.List;

/** No dev server, no build step — assets are served as-is. Both methods below are unreachable: call sites are gated by {@link FrontendType#requiresOrchestration()}. */
final class StaticFrontendStrategy implements FrontendStrategy {
    @Override
    public FrontendType type() {
        return FrontendType.STATIC;
    }

    @Override
    public List<String> devCommand(WebBundlerConfig config, PackageManagerAdapter adapter) {
        throw new UnsupportedOperationException("STATIC frontend type has no dev command");
    }

    @Override
    public List<String> buildCommand(WebBundlerConfig config, PackageManagerAdapter adapter) {
        throw new UnsupportedOperationException("STATIC frontend type has no build command");
    }
}
