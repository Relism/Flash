package dev.relism.flash.ext.webbundler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class WebBundlerConfig {
    private final RuntimeMode runtimeMode;
    private final OperationMode operationMode;
    private final FrontendType frontendType;
    private final PackageManager packageManager;
    private final InstallPolicy installPolicy;
    private final LoggingMode loggingMode;
    private final CommandSafetyMode commandSafetyMode;
    private final BasePathEnforcementMode basePathEnforcement;
    private final Path webRoot;
    private final String basePath;
    private final String devHost;
    private final int devPort;
    private final AssetsSource assetsSource;
    private final String indexFile;
    private final List<String> watchList;
    private final String devCommand;
    private final String buildCommand;
    private final String installCommand;
    private final List<String> safeCommandRegistry;
    private final String envRuntimeVariable;

    private WebBundlerConfig(Builder b) {
        this.runtimeMode = b.runtimeMode;
        this.operationMode = b.operationMode;
        this.frontendType = b.frontendType;
        this.packageManager = b.packageManager;
        this.installPolicy = b.installPolicy;
        this.loggingMode = b.loggingMode;
        this.commandSafetyMode = b.commandSafetyMode;
        this.basePathEnforcement = b.basePathEnforcement;
        this.webRoot = b.webRoot.normalize();
        this.basePath = sanitizeBasePath(b.basePath);
        this.devHost = b.devHost;
        this.devPort = b.devPort;
        this.assetsSource = b.assetsSource;
        this.indexFile = b.indexFile;
        this.watchList = List.copyOf(b.watchList);
        this.devCommand = blankToNull(b.devCommand);
        this.buildCommand = blankToNull(b.buildCommand);
        this.installCommand = blankToNull(b.installCommand);
        this.safeCommandRegistry = List.copyOf(b.safeCommandRegistry);
        this.envRuntimeVariable = b.envRuntimeVariable;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public RuntimeMode runtimeMode() { return runtimeMode; }
    public OperationMode operationMode() { return operationMode; }
    public FrontendType frontendType() { return frontendType; }
    public PackageManager packageManager() { return packageManager; }
    public InstallPolicy installPolicy() { return installPolicy; }
    public LoggingMode loggingMode() { return loggingMode; }
    public CommandSafetyMode commandSafetyMode() { return commandSafetyMode; }
    public BasePathEnforcementMode basePathEnforcement() { return basePathEnforcement; }
    public Path webRoot() { return webRoot; }
    public String basePath() { return basePath; }
    public String devHost() { return devHost; }
    public int devPort() { return devPort; }
    public AssetsSource assetsSource() { return assetsSource; }
    public String indexFile() { return indexFile; }
    public List<String> watchList() { return watchList; }
    public String devCommand() { return devCommand; }
    public String buildCommand() { return buildCommand; }
    public String installCommand() { return installCommand; }
    public List<String> safeCommandRegistry() { return safeCommandRegistry; }
    public String envRuntimeVariable() { return envRuntimeVariable; }

    public Path expectedLockfile() {
        return webRoot.resolve(packageManager.lockfileName());
    }

    private void validate() {
        Objects.requireNonNull(runtimeMode, "runtimeMode");
        Objects.requireNonNull(operationMode, "operationMode");
        Objects.requireNonNull(frontendType, "frontendType");
        Objects.requireNonNull(packageManager, "packageManager");
        Objects.requireNonNull(installPolicy, "installPolicy");
        Objects.requireNonNull(loggingMode, "loggingMode");
        Objects.requireNonNull(commandSafetyMode, "commandSafetyMode");
        Objects.requireNonNull(basePathEnforcement, "basePathEnforcement");
        Objects.requireNonNull(webRoot, "webRoot");
        Objects.requireNonNull(assetsSource, "assetsSource");
        Objects.requireNonNull(indexFile, "indexFile");
        if (frontendType.requiresOrchestration()) {
            if (devPort <= 0 || devPort > 65535) {
                throw new IllegalArgumentException("WebBundlerConfig: devPort must be in range 1..65535");
            }
            if (watchList.isEmpty()) {
                throw new IllegalArgumentException("WebBundlerConfig: watchList must not be empty");
            }
            for (String path : watchList) {
                if (path == null || path.isBlank()) {
                    throw new IllegalArgumentException("WebBundlerConfig: watchList contains blank entries");
                }
            }
        }
        if (!basePath.startsWith("/")) {
            throw new IllegalArgumentException("WebBundlerConfig: basePath must start with '/'");
        }
    }

    private static String sanitizeBasePath(String raw) {
        if (raw == null || raw.isBlank()) return "/";
        String normalized = raw.trim();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class Builder {
        private RuntimeMode runtimeMode = RuntimeMode.AUTODETECT;
        private OperationMode operationMode = OperationMode.ORCHESTRATE_ONLY;
        private FrontendType frontendType = FrontendType.VITE;
        private PackageManager packageManager = PackageManager.NPM;
        private InstallPolicy installPolicy = InstallPolicy.AUTO_IF_LOCK_HASH_CHANGED;
        private LoggingMode loggingMode = LoggingMode.MERGED;
        private CommandSafetyMode commandSafetyMode = CommandSafetyMode.WARN;
        private BasePathEnforcementMode basePathEnforcement = BasePathEnforcementMode.WARN_ONLY;
        private Path webRoot = Path.of("web");
        private String basePath = "/";
        private String devHost = "127.0.0.1";
        private int devPort = 5173;
        private AssetsSource assetsSource = defaultAssetsSource(FrontendType.VITE);
        private String indexFile = "index.html";
        private List<String> watchList = defaultWatchList(PackageManager.NPM);
        private String devCommand;
        private String buildCommand;
        private String installCommand;
        private List<String> safeCommandRegistry = defaultSafeRegistry();
        private String envRuntimeVariable = "FLASH_WEB_BUNDLER_MODE";

        public Builder runtimeMode(RuntimeMode runtimeMode) { this.runtimeMode = runtimeMode; return this; }
        public Builder operationMode(OperationMode operationMode) { this.operationMode = operationMode; return this; }
        public Builder frontendType(FrontendType frontendType) {
            this.frontendType = frontendType;
            this.assetsSource = defaultAssetsSource(frontendType);
            this.operationMode = frontendType.requiresOrchestration() ? OperationMode.ORCHESTRATE_ONLY : OperationMode.MANAGED;
            return this;
        }
        public Builder packageManager(PackageManager packageManager) {
            this.packageManager = packageManager;
            this.watchList = defaultWatchList(packageManager);
            return this;
        }
        public Builder installPolicy(InstallPolicy installPolicy) { this.installPolicy = installPolicy; return this; }
        public Builder loggingMode(LoggingMode loggingMode) { this.loggingMode = loggingMode; return this; }
        public Builder commandSafetyMode(CommandSafetyMode commandSafetyMode) { this.commandSafetyMode = commandSafetyMode; return this; }
        public Builder basePathEnforcement(BasePathEnforcementMode basePathEnforcement) { this.basePathEnforcement = basePathEnforcement; return this; }
        public Builder webRoot(Path webRoot) { this.webRoot = webRoot; return this; }
        public Builder basePath(String basePath) { this.basePath = basePath; return this; }
        public Builder devHost(String devHost) { this.devHost = devHost; return this; }
        public Builder devPort(int devPort) { this.devPort = devPort; return this; }
        public Builder assetsSource(AssetsSource assetsSource) { this.assetsSource = assetsSource; return this; }
        public Builder assetsFromFilesystem(Path distDir) {
            this.assetsSource = FilesystemAssetsSource.of(distDir);
            return this;
        }
        public Builder assetsFromClasspath(String rootPrefix) {
            this.assetsSource = ClasspathAssetsSource.of(rootPrefix);
            return this;
        }
        public Builder assetsFromClasspath(String rootPrefix, String manifestResource) {
            this.assetsSource = ClasspathAssetsSource.of(rootPrefix, manifestResource);
            return this;
        }
        public Builder indexFile(String indexFile) { this.indexFile = indexFile; return this; }
        public Builder watchList(List<String> watchList) { this.watchList = new ArrayList<>(watchList); return this; }
        public Builder devCommand(String devCommand) { this.devCommand = devCommand; return this; }
        public Builder buildCommand(String buildCommand) { this.buildCommand = buildCommand; return this; }
        public Builder installCommand(String installCommand) { this.installCommand = installCommand; return this; }
        public Builder safeCommandRegistry(List<String> safeCommandRegistry) {
            this.safeCommandRegistry = new ArrayList<>(safeCommandRegistry);
            return this;
        }
        public Builder envRuntimeVariable(String envRuntimeVariable) { this.envRuntimeVariable = envRuntimeVariable; return this; }

        public WebBundlerConfig build() {
            return new WebBundlerConfig(this);
        }

        private static AssetsSource defaultAssetsSource(FrontendType type) {
            return type == FrontendType.STATIC
                    ? FilesystemAssetsSource.of(Path.of("."))
                    : FilesystemAssetsSource.of(Path.of("dist"));
        }

        private static List<String> defaultWatchList(PackageManager manager) {
            return List.of(
                    "package.json",
                    manager.lockfileName(),
                    "vite.config.ts",
                    "vite.config.js",
                    "tsconfig.json",
                    ".env",
                    ".env.local"
            );
        }

        private static List<String> defaultSafeRegistry() {
            return List.of(
                    "npm", "pnpm", "yarn", "bun", "npx",
                    "npm.cmd", "pnpm.cmd", "yarn.cmd", "bun.cmd", "npx.cmd"
            );
        }
    }
}
