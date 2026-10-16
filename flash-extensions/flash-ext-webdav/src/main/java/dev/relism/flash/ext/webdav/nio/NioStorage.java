package dev.relism.flash.ext.webdav.nio;

import dev.relism.flash.ext.webdav.DavNode;
import dev.relism.flash.ext.webdav.DavPath;
import dev.relism.flash.ext.webdav.DavStorage;
import dev.relism.flash.ext.webdav.internal.OwnSpace;
import dev.relism.flash.ext.webdav.internal.Space;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import javax.xml.namespace.QName;

/**
 * A directory of any {@code java.nio.file} file system. A symbolic link is followed only while it stays
 * inside the root; one that leads out is refused, and left out of listings. A clustered directory keeps
 * its {@link ClusterSpace} in {@code .dav} under the root, which no path reaches unless the handler
 * shows it.
 */
class NioStorage implements DavStorage.Writable, OwnSpace {

    private final Path root;
    private final FileStore store;
    /** The root's provider: reads what is at a path, {@code null} when nothing is. */
    private final FileSystemProvider files;
    /** The state several processes share; {@code null} when one process serves the directory. */
    private final ClusterSpace space;
    /** Whether a clustered directory's {@code .dav} is out of the clients' reach. */
    private final boolean stateHidden;

    private NioStorage(Path root, FileStore store, boolean clustered, boolean stateHidden) {
        this.root = root;
        this.store = store;
        this.files = root.getFileSystem().provider();
        this.stateHidden = stateHidden;
        this.space = clustered ? ClusterSpace.of(root, this) : null;
    }

    /** A path under the root and what is there, {@code null} when nothing is. */
    record Found(Path file, BasicFileAttributes attributes) {}

    /**
     * The directory at {@code root}, created when missing, with dead properties in extended attributes
     * where the store has them. A clustered one has its state made, or checked, here.
     */
    static NioStorage of(Path root, boolean clustered, boolean stateHidden) throws IOException {
        Path real = Files.createDirectories(root).toRealPath();
        FileStore store = Files.getFileStore(real);
        NioStorage storage = store.supportsFileAttributeView(UserDefinedFileAttributeView.class)
                ? new Attributed(real, store, clustered, stateHidden) : new NioStorage(real, store, clustered, stateHidden);
        if (clustered) storage.space.enter().close();
        return storage;
    }

    @Override
    public Space space() {
        return space;
    }

    @Override
    public Optional<DavNode> stat(DavPath path) throws IOException {
        BasicFileAttributes attributes = find(path).attributes();
        return attributes == null ? Optional.empty() : Optional.of(node(path.name(), attributes));
    }

    @Override
    public List<DavNode> list(DavPath collection) throws IOException {
        List<DavNode> members = new ArrayList<>();
        boolean top = collection.isRoot();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(file(collection))) {
            for (Path each : entries) {
                try {
                    String name = each.getFileName().toString();
                    BasicFileAttributes attributes = attributes(each);
                    if (attributes != null && !(top && kept(name, attributes))) members.add(node(name, attributes));
                } catch (AccessDeniedException | NoSuchFileException outsideOrGone) {
                    // a link out of the root, or a link target deleted meanwhile
                }
            }
        }
        return members;
    }

    @Override
    public InputStream read(DavPath file, long offset, long length) throws IOException {
        FileChannel channel = FileChannel.open(file(file), StandardOpenOption.READ).position(offset);
        return new java.io.FilterInputStream(Channels.newInputStream(channel)) {
            private long left = length;

            @Override
            public int read() throws IOException {
                if (left <= 0) return -1;
                int b = super.read();
                if (b >= 0) left--;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (left <= 0) return -1;
                int n = super.read(b, off, (int) Math.min(len, left));
                if (n > 0) left -= n;
                return n;
            }
        };
    }

    /** Sums sizes without following links; what cannot be read is not counted, nor is {@code .dav}. */
    @Override
    public long usage(DavPath path) throws IOException {
        long[] bytes = {0};
        Files.walkFileTree(file(path), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                boolean state = root.equals(dir.getParent()) && state(dir.getFileName().toString(), attributes);
                return state ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) bytes[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes[0];
    }

    @Override
    public OptionalLong free() throws IOException {
        return OptionalLong.of(store.getUsableSpace());
    }

    /**
     * Writes into a file of its own, beside the target or in {@code .dav/uploads} when clustered, then
     * moves it over the target, so a broken upload leaves the old content whole.
     */
    @Override
    public void write(DavPath file, InputStream body, OptionalLong length) throws IOException {
        replace(file(file), body);
    }

    final void replace(Path target, InputStream body) throws IOException {
        Path partial = space == null ? Files.createTempFile(target.getParent(), ".dav-", ".part") : space.part();
        try {
            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    @Override
    public void mkdir(DavPath collection) throws IOException {
        Files.createDirectory(file(collection));
    }

    @Override
    public void delete(DavPath path) throws IOException {
        Files.delete(file(path));
    }

    @Override
    public void copy(DavPath from, DavPath to) throws IOException {
        Files.copy(file(from), file(to));
    }

    @Override
    public void move(DavPath from, DavPath to) throws IOException {
        Files.move(file(from), file(to));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NioStorage nio && nio.root.equals(root) && (nio.space == null) == (space == null)
                && nio.stateHidden == stateHidden;
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    /** {@code path} under the root, refused when a link leads it out. */
    final Path file(DavPath path) throws IOException {
        return find(path).file();
    }

    /**
     * Walks {@code path} down from the root one name at a time, reading what each one is without
     * following it: one lookup per name, and a link is resolved only where one is met. Below a file
     * or below nothing there is nothing, so the walk stops reading there.
     */
    final Found find(DavPath path) throws IOException {
        if (path.segments().isEmpty()) return new Found(root, Files.readAttributes(root, BasicFileAttributes.class));
        Path file = root;
        BasicFileAttributes attributes = null;
        boolean below = true; // still inside an existing directory
        for (String segment : path.segments()) {
            boolean top = file == root;
            Path next;
            try {
                next = file.resolve(segment);
            } catch (InvalidPathException unstorable) {
                throw new AccessDeniedException(segment, null, "this file system cannot hold the name (is the JVM's sun.jnu.encoding UTF-8?)");
            }
            if (!file.equals(next.getParent())) throw new AccessDeniedException(segment, null, "not a plain name on this file system");
            file = next;
            attributes = below ? attributes(file) : null;
            if (top && kept(segment, attributes)) throw new AccessDeniedException(path.toString(), null, "kept for the server's state");
            below = attributes != null && attributes.isDirectory();
        }
        return new Found(file, attributes);
    }

    /**
     * What is at {@code file}, a link followed when it leads somewhere under the root; {@code null}
     * when nothing is, a link to nothing included. A link out of the root is refused.
     *
     * <p>A name that is not there costs the JDK an exception inside {@code readAttributesIfExists}:
     * it reads without one only when following links. Reading that way first would spare a miss the
     * exception but cost every hit a second lookup, and hits are most of the traffic.
     */
    private BasicFileAttributes attributes(Path file) throws IOException {
        BasicFileAttributes attributes = files.readAttributesIfExists(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes == null || !attributes.isSymbolicLink()) return attributes;
        Path target;
        try {
            target = file.toRealPath();
        } catch (NoSuchFileException dangling) {
            return null;
        }
        if (!target.startsWith(root)) throw new AccessDeniedException(file.toString(), null, "outside the root");
        if (space != null && stateHidden && !target.equals(root)) {
            Path top = root.resolve(root.relativize(target).getName(0));
            if (kept(top.getFileName().toString(), files.readAttributesIfExists(top, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)))
                throw new AccessDeniedException(file.toString(), null, "kept for the server's state");
        }
        return files.readAttributesIfExists(target, BasicFileAttributes.class);
    }

    /**
     * Whether a name right under the root is a clustered directory's {@code .dav}, however the file
     * system spells it: by file key where there is one, so a name that only folds to it on a
     * case-insensitive store counts too; on Windows, which has none, by its rules: case folded,
     * trailing dots and spaces dropped.
     */
    private boolean state(String name, BasicFileAttributes attributes) {
        if (space == null) return false;
        if (name.equals(ClusterSpace.NAME)) return true;
        Object key = space.key();
        if (key != null) return attributes != null && key.equals(attributes.fileKey());
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) end--;
        return name.substring(0, end).equalsIgnoreCase(ClusterSpace.NAME);
    }

    /** Whether a name right under the root is the state, and clients may not reach it. */
    private boolean kept(String name, BasicFileAttributes attributes) {
        return stateHidden && state(name, attributes);
    }

    private static DavNode node(String name, BasicFileAttributes attributes) {
        Instant modified = attributes.lastModifiedTime().toInstant();
        DavNode node = attributes.isDirectory() ? DavNode.collection(name, modified) : DavNode.file(name, attributes.size(), modified);
        return new DavNode(name, node.collection(), node.length(), modified, attributes.creationTime().toInstant(), node.etag(),
                node.contentType());
    }

    /** A directory whose file store has extended attributes: one user attribute per dead property, {@code dav:{ns}name}. */
    static final class Attributed extends NioStorage implements DavStorage.Properties {

        private static final String PREFIX = "dav:";

        private Attributed(Path root, FileStore store, boolean clustered, boolean stateHidden) {
            super(root, store, clustered, stateHidden);
        }

        /** Keeps the dead properties of a file it replaces. */
        @Override
        public void write(DavPath file, InputStream body, OptionalLong length) throws IOException {
            Found target = find(file);
            Map<QName, String> kept = target.attributes() == null ? Map.of() : properties(target.file());
            replace(target.file(), body);
            if (!kept.isEmpty()) properties(target.file(), kept);
        }

        @Override
        public Map<QName, String> properties(DavPath path) throws IOException {
            return properties(file(path));
        }

        private Map<QName, String> properties(Path file) throws IOException {
            UserDefinedFileAttributeView view = view(file);
            Map<QName, String> properties = new HashMap<>();
            for (String name : view.list()) {
                if (!name.startsWith(PREFIX)) continue;
                ByteBuffer value = ByteBuffer.allocate(view.size(name));
                view.read(name, value);
                properties.put(qname(name.substring(PREFIX.length())), new String(value.array(), 0, value.position(), StandardCharsets.UTF_8));
            }
            return properties;
        }

        @Override
        public void properties(DavPath path, Map<QName, String> properties) throws IOException {
            properties(file(path), properties);
        }

        private void properties(Path file, Map<QName, String> properties) throws IOException {
            UserDefinedFileAttributeView view = view(file);
            for (String name : view.list())
                if (name.startsWith(PREFIX) && !properties.containsKey(qname(name.substring(PREFIX.length())))) view.delete(name);
            for (Map.Entry<QName, String> property : properties.entrySet())
                view.write(PREFIX + clark(property.getKey()), ByteBuffer.wrap(property.getValue().getBytes(StandardCharsets.UTF_8)));
        }

        /** {@code {ns}name}, the attribute's name; {@code QName.valueOf} refuses an empty namespace. */
        private static String clark(QName name) {
            return "{" + name.getNamespaceURI() + "}" + name.getLocalPart();
        }

        private static QName qname(String clark) {
            int close = clark.indexOf('}');
            return new QName(clark.substring(1, close), clark.substring(close + 1));
        }

        private static UserDefinedFileAttributeView view(Path file) {
            return Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
        }
    }
}
