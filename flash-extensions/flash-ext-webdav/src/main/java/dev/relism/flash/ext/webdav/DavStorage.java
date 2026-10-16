package dev.relism.flash.ext.webdav;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import javax.xml.namespace.QName;

/**
 * What a {@link DavHandler} serves one request from: a tree of resources, rooted at the mount. A
 * storage answers what a resource is and moves bytes; the handler speaks the protocol, keeps locks and
 * counts the quota.
 *
 * <p>What a storage can do is in its type. One that only implements this interface is read-only;
 * {@link Writable} adds writing, {@link Properties} keeps the properties clients set. Without
 * {@code Properties}, the handler keeps them in memory.
 *
 * <p>The handler keys locks, the bytes in use and properties kept in memory by the storage's
 * {@code equals}: two storages that show the same resources are equal. A record over its location
 * gets this for free.
 */
public interface DavStorage {

    /** The resource at {@code path}, empty when there is none. */
    Optional<DavNode> stat(DavPath path) throws IOException;

    /** The members of a collection. */
    List<DavNode> list(DavPath collection) throws IOException;

    /** Exactly {@code length} bytes of a file from {@code offset}, then the end of the stream. */
    InputStream read(DavPath file, long offset, long length) throws IOException;

    /** The bytes held by the files at {@code path} and below it. By default, the sum over {@link #list}. */
    default long usage(DavPath path) throws IOException {
        long bytes = 0;
        for (DavNode member : list(path)) bytes += member.collection() ? usage(path.child(member.name())) : member.length();
        return bytes;
    }

    /** The bytes the storage can still take, when it knows. */
    default OptionalLong free() throws IOException {
        return OptionalLong.empty();
    }

    /** A storage clients can write to. */
    interface Writable extends DavStorage {

        /**
         * Creates or replaces a file with {@code body}, whose length is known when the client sent it.
         * Reads the body to its end on the calling thread before the new content shows: at its end the
         * handler checks the request again, and holds the space until this returns.
         */
        void write(DavPath file, InputStream body, OptionalLong length) throws IOException;

        /** Creates a collection whose parent exists. */
        void mkdir(DavPath collection) throws IOException;

        /** Removes a file or an empty collection. */
        void delete(DavPath path) throws IOException;

        /** Copies one resource to a free path: a file's content, or an empty collection. By default, read then write. */
        default void copy(DavPath from, DavPath to) throws IOException {
            DavNode node = stat(from).orElseThrow(() -> new NoSuchFileException(from.toString()));
            if (node.collection()) mkdir(to);
            else try (InputStream in = read(from, 0, node.length())) {
                write(to, in, OptionalLong.of(node.length()));
            }
        }

        /** Moves a resource and everything below it to a free path. By default, copy then delete, properties included. */
        default void move(DavPath from, DavPath to) throws IOException {
            DavNode node = stat(from).orElseThrow(() -> new NoSuchFileException(from.toString()));
            copy(from, to);
            if (this instanceof Properties properties) properties.properties(to, properties.properties(from));
            if (node.collection())
                for (DavNode member : list(from)) move(from.child(member.name()), to.child(member.name()));
            delete(from);
        }
    }

    /**
     * A storage that keeps the properties clients set. Each value is the element's content as XML,
     * with every namespace it uses declared in it.
     */
    interface Properties extends DavStorage {

        /** The properties of a resource; empty when it has none. */
        Map<QName, String> properties(DavPath path) throws IOException;

        /** Replaces the properties of a resource. */
        void properties(DavPath path, Map<QName, String> properties) throws IOException;
    }
}
