package dev.relism.flash.ext.webbundler;

public enum PackageManager {
    NPM("npm", "package-lock.json"),
    PNPM("pnpm", "pnpm-lock.yaml"),
    YARN("yarn", "yarn.lock"),
    BUN("bun", "bun.lockb");

    private final String binary;
    private final String lockfileName;

    PackageManager(String binary, String lockfileName) {
        this.binary = binary;
        this.lockfileName = lockfileName;
    }

    public String binary() {
        return binary;
    }

    public String lockfileName() {
        return lockfileName;
    }
}
