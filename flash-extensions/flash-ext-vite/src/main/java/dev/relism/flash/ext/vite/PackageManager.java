package dev.relism.flash.ext.vite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The package manager a frontend uses, told apart by its lockfile. Shared with the Maven plugin. */
public enum PackageManager {
    PNPM("pnpm-lock.yaml", "install", "--frozen-lockfile"),
    YARN("yarn.lock", "install", "--frozen-lockfile"),
    BUN("bun.lock", "install", "--frozen-lockfile"),
    NPM("package-lock.json", "ci");

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private final String lockfile;
    private final String[] install;

    PackageManager(String lockfile, String... install) {
        this.lockfile = lockfile;
        this.install = install;
    }

    /** A package manager and the lockfile it was found by, {@code null} when there is none. */
    public record Found(PackageManager manager, Path lockfile) {

        /** Installs exactly what the lockfile pins; without one, npm resolves afresh. */
        public List<String> install() {
            if (lockfile == null) return List.of(manager.binary(), "install");
            List<String> command = new ArrayList<>(List.of(manager.binary()));
            command.addAll(List.of(manager.install));
            return command;
        }

        /** Runs a {@code package.json} script, passing {@code args} through to it. */
        public List<String> run(String script, String... args) {
            List<String> command = new ArrayList<>(List.of(manager.binary(), "run", script));
            if (manager == NPM && args.length > 0) command.add("--");
            command.addAll(List.of(args));
            return command;
        }
    }

    /**
     * The nearest lockfile at or above {@code root}, so a project inside a workspace finds the
     * workspace's. None at all is npm without a lockfile.
     */
    public static Found of(Path root) {
        for (Path dir = root.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            for (PackageManager manager : values()) {
                Path lockfile = dir.resolve(manager.lockfile);
                if (Files.isRegularFile(lockfile)) return new Found(manager, lockfile);
            }
        }
        return new Found(NPM, null);
    }

    private String binary() {
        String name = name().toLowerCase();
        return WINDOWS ? name + ".cmd" : name;
    }
}
