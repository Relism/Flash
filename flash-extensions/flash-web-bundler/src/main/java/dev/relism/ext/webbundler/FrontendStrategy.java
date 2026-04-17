package dev.relism.ext.webbundler;

import java.util.List;

interface FrontendStrategy {
    FrontendType type();
    List<String> devCommand(WebBundlerConfig config, PackageManagerAdapter adapter);
    List<String> buildCommand(WebBundlerConfig config, PackageManagerAdapter adapter);
    default String healthcheckPath() {
        return "/";
    }
}
