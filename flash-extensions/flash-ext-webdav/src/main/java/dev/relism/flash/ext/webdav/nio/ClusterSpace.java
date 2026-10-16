package dev.relism.flash.ext.webdav.nio;

import dev.relism.flash.ext.webdav.DavPath;
import dev.relism.flash.ext.webdav.DavStorage;
import dev.relism.flash.ext.webdav.internal.Locks.Lock;
import dev.relism.flash.ext.webdav.internal.Space;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32C;

/**
 * The space of a directory that several processes serve, on one host or several. Its state lives
 * beside the files, in {@code .dav} under the root, and every process reads and writes it under a
 * lock of {@code .dav/state}: all of them count the same bytes and honor the same locks.
 *
 * <p>The state holds the bytes of finished files, the bytes each process's uploads have taken, and
 * the lock table. A process that takes bytes or changes files holds a slot, a one-byte lock past the
 * end of the file, which its operating system releases when it dies. A free slot with bytes in the
 * state is a dead process's: its bytes are given back, its partial uploads in {@code .dav/uploads}
 * deleted, and the finished files counted again, since it may have died between changing a file and
 * counting it. A missing or torn state starts over the same way, and each live process writes its
 * own share back the next time it holds the space.
 */
final class ClusterSpace extends Space {

    static final String NAME = ".dav";

    private static final int MAGIC = 0x46444156; // "FDAV"
    /** magic, checksum, version, locks version, committed, entry count; then entries, then the lock table */
    private static final int HEAD = 36;
    private static final int ENTRY = 12;
    private static final long SPACE = 1L << 40;
    private static final long SLOTS = SPACE + 1;
    private static final Object GONE = new Object();
    /** One per directory in this JVM: closing a second channel on the file would release the first one's locks. */
    private static final Map<Path, ClusterSpace> OPEN = new ConcurrentHashMap<>();

    private final Path dir;
    private final Path file;
    private final Path uploads;
    private volatile Object key;
    private volatile int slot = -1;

    private FileChannel channel;
    private Object channelKey;
    private FileLock held;
    private int operations;
    private boolean recovered;

    // The state as this process last read or wrote it
    private long version;
    private long locksVersion;
    private long storedCommitted;
    private long storedMine = -1;
    private boolean forced;
    private int entries;
    private int[] slots = new int[4];
    private long[] amounts = new long[4];

    private ByteBuffer buffer = ByteBuffer.allocate(4096);
    private ByteBuffer front = ByteBuffer.allocate(HEAD + 4 * ENTRY);
    private ByteBuffer table = ByteBuffer.wrap(new byte[4]);
    private final ByteBuffer[] gather = new ByteBuffer[2];
    private final CRC32C crc = new CRC32C();

    private ClusterSpace(Path root, DavStorage storage) {
        super(storage);
        this.dir = root.resolve(NAME);
        this.file = dir.resolve("state");
        this.uploads = dir.resolve("uploads");
    }

    static ClusterSpace of(Path root, DavStorage storage) {
        return OPEN.computeIfAbsent(root, ignored -> new ClusterSpace(root, storage));
    }

    /** The file key of {@code .dav}, by which a name that leads to it is known; {@code null} where the file system has none. */
    Object key() {
        return key;
    }

    /** A new file for an upload, named by this process's slot so that a dead process's are found. */
    Path part() throws IOException {
        return Files.createTempFile(uploads, slot + "-", ".part");
    }

    @Override
    protected void load() throws IOException {
        try {
            open();
            read();
            if (operations > 0 && slot < 0) claim(); // the state was replaced while this process was changing files
            if (!recovered) {
                recovered = true;
                recover();
            }
        } catch (IOException | RuntimeException failure) {
            drop();
            throw failure;
        }
    }

    @Override
    protected void unload() throws IOException {
        try {
            store();
        } finally {
            if (operations == 0) drop();
            else held.release();
        }
    }

    @Override
    protected long others() {
        long bytes = 0;
        for (int i = 0; i < entries; i++) bytes += amounts[i];
        return bytes;
    }

    @Override
    public void begin() throws IOException {
        if (operations++ > 0) return;
        claim();
        store(); // the slot is in the state before any file changes
    }

    @Override
    public void end() {
        operations--;
    }

    @Override
    public boolean recover() throws IOException {
        boolean dead = false;
        for (int i = entries - 1; i >= 0; i--) {
            if (alive(slots[i])) continue;
            entries--;
            slots[i] = slots[entries];
            amounts[i] = amounts[entries];
            dead = true;
        }
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(uploads)) {
            for (Path part : parts) if (!alive(slotOf(part))) Files.deleteIfExists(part);
        }
        if (dead) committed = -1;
        return dead;
    }

    /** Locks the state file; reopens it when it was deleted or replaced since it was opened. */
    private void open() throws IOException {
        while (true) {
            if (channel == null) {
                prepare();
                channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                channelKey = key(file);
            }
            try {
                held = channel.lock(SPACE, 1, false);
            } catch (IOException | UnsupportedOperationException failure) {
                throw new IllegalStateException("a clustered directory needs file locks, and " + file + " could not be locked: "
                        + failure.getMessage(), failure);
            }
            if (Objects.equals(key(file), channelKey)) return;
            drop();
        }
    }

    /** Creates {@code .dav} when missing, and refuses one this module did not make. */
    private void prepare() throws IOException {
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.notExists(file)) try (DirectoryStream<Path> found = Files.newDirectoryStream(dir)) {
                for (Path each : found) if (!each.equals(uploads) && !each.equals(file)) throw foreign();
            }
        } else {
            try {
                Files.createDirectory(dir);
                hide(dir);
            } catch (FileAlreadyExistsException raced) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw foreign();
            }
        }
        Files.createDirectories(uploads);
        key = Files.readAttributes(dir, BasicFileAttributes.class).fileKey();
    }

    /** Closes the state file, which releases every lock this process holds on it, its slot included. */
    private void drop() throws IOException {
        held = null;
        slot = -1;
        if (channel == null) return;
        FileChannel closing = channel;
        channel = null;
        closing.close();
    }

    private void read() throws IOException {
        long size = channel.size();
        if (size == 0) {
            reset(); // a new space
            return;
        }
        if (size > buffer.capacity()) buffer = ByteBuffer.allocate(Math.toIntExact(size));
        buffer.clear().limit((int) size);
        while (buffer.hasRemaining() && channel.read(buffer, buffer.position()) >= 0) {}
        buffer.flip();
        if (buffer.limit() >= 4 && buffer.getInt(0) != MAGIC) throw foreign();
        if (buffer.limit() < HEAD || buffer.getInt(4) != checksum(buffer, buffer.limit(), null)) {
            reset();
            return;
        }
        long read = buffer.getLong(8);
        if (read == version) return; // as this process last left it
        version = read;
        committed = storedCommitted = buffer.getLong(24);
        entries = 0;
        storedMine = -1;
        int count = buffer.getInt(32);
        buffer.position(HEAD);
        for (int i = 0; i < count; i++) {
            int owner = buffer.getInt();
            long bytes = buffer.getLong();
            if (owner == slot) storedMine = bytes;
            else add(owner, bytes);
        }
        long readLocks = buffer.getLong(16);
        if (readLocks == locksVersion) return;
        int start = buffer.position();
        locks().load(decode(buffer));
        table = ByteBuffer.wrap(Arrays.copyOfRange(buffer.array(), start, buffer.limit()));
        locksVersion = readLocks;
    }

    /** A state with nothing in it: the finished files are counted when needed, and live processes write their share back. */
    private void reset() {
        version = locksVersion = ThreadLocalRandom.current().nextLong();
        committed = -1;
        entries = 0;
        storedMine = -1;
        locks().load(List.of());
        table = ByteBuffer.wrap(new byte[4]);
        forced = true;
        recovered = false;
    }

    private void store() throws IOException {
        long mine = operations > 0 ? reserved : -1;
        if (!forced && !locks().changed() && committed == storedCommitted && mine == storedMine) return;
        long next = version + 1;
        version = 0; // until written: after a failed write this process reads the file again
        if (locks().changed()) {
            table = encode(locks().all());
            locksVersion = next;
            locks().saved();
        }
        int count = entries + (mine >= 0 ? 1 : 0);
        if (front.capacity() < HEAD + count * ENTRY) front = ByteBuffer.allocate(HEAD + 2 * count * ENTRY);
        front.clear().putInt(MAGIC).putInt(0).putLong(next).putLong(locksVersion).putLong(committed).putInt(count);
        for (int i = 0; i < entries; i++) front.putInt(slots[i]).putLong(amounts[i]);
        if (mine >= 0) front.putInt(slot).putLong(mine);
        front.flip();
        front.putInt(4, checksum(front, front.limit(), table));
        gather[0] = front;
        gather[1] = table.rewind();
        channel.position(0);
        while (front.hasRemaining() || table.hasRemaining()) channel.write(gather);
        channel.truncate(front.limit() + table.limit());
        version = next;
        storedCommitted = committed;
        storedMine = mine;
        forced = false;
    }

    /** Holds the first slot no process holds. */
    private void claim() throws IOException {
        for (int candidate = 0; ; candidate++) {
            if (listed(candidate)) continue;
            if (channel.tryLock(SLOTS + candidate, 1, false) == null) continue;
            slot = candidate;
            return;
        }
    }

    /** Whether the process with this slot still runs: its operating system holds the slot's lock until it ends. */
    private boolean alive(int owner) throws IOException {
        if (owner == slot) return true;
        try {
            FileLock free = channel.tryLock(SLOTS + owner, 1, false);
            if (free == null) return true;
            free.release();
            return false;
        } catch (OverlappingFileLockException ours) {
            return true;
        }
    }

    private boolean listed(int owner) {
        for (int i = 0; i < entries; i++) if (slots[i] == owner) return true;
        return false;
    }

    private void add(int owner, long bytes) {
        if (entries == slots.length) {
            slots = Arrays.copyOf(slots, entries * 2);
            amounts = Arrays.copyOf(amounts, entries * 2);
        }
        slots[entries] = owner;
        amounts[entries++] = bytes;
    }

    private int checksum(ByteBuffer head, int length, ByteBuffer rest) {
        crc.reset();
        crc.update(head.array(), 8, length - 8);
        if (rest != null) crc.update(rest.array(), 0, rest.limit());
        return (int) crc.getValue();
    }

    private IllegalStateException foreign() {
        return new IllegalStateException(dir + " is not the state of a clustered WebDAV directory: move it away, or serve another directory");
    }

    private static int slotOf(Path part) {
        String name = part.getFileName().toString();
        int dash = name.indexOf('-', 1);
        try {
            return dash < 0 ? -1 : Integer.parseInt(name, 0, dash, 10);
        } catch (NumberFormatException foreign) {
            return -1;
        }
    }

    private static Object key(Path file) throws IOException {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
        } catch (NoSuchFileException deleted) {
            return GONE;
        }
    }

    /** Windows shows names that start with a dot: mark the directory hidden there. */
    private static void hide(Path dir) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) return;
        try {
            Files.setAttribute(dir, "dos:hidden", true);
        } catch (UnsupportedOperationException nothingToMark) {
            // a file system without DOS attributes
        }
    }

    private static ByteBuffer encode(List<Lock> locks) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(locks.size());
        for (Lock lock : locks) {
            text(out, lock.token());
            out.writeInt(lock.root().segments().size());
            for (String segment : lock.root().segments()) text(out, segment);
            out.writeBoolean(lock.exclusive());
            out.writeBoolean(lock.deep());
            text(out, lock.owner());
            out.writeLong(lock.seconds());
            out.writeLong(lock.expiresAt());
        }
        return ByteBuffer.wrap(bytes.toByteArray());
    }

    private static List<Lock> decode(ByteBuffer in) {
        int count = in.getInt();
        List<Lock> locks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String token = text(in);
            DavPath root = DavPath.ROOT;
            for (int segments = in.getInt(); segments > 0; segments--) root = root.child(text(in));
            locks.add(new Lock(token, root, in.get() != 0, in.get() != 0, text(in), in.getLong(), in.getLong()));
        }
        return locks;
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(ByteBuffer in) {
        int length = in.getInt();
        String text = new String(in.array(), in.position(), length, StandardCharsets.UTF_8);
        in.position(in.position() + length);
        return text;
    }
}
