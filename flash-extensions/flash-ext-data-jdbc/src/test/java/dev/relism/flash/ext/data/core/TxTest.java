package dev.relism.flash.ext.data.core;

import dev.relism.flash.http.HttpException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What leaves a transaction, and whose transaction it is. */
class TxTest {

    /** Records what it was asked to do; no database. */
    static final class Recording implements TxManager {
        final List<String> calls = new ArrayList<>();

        @Override public TxStatus begin(TxDefinition definition) {
            calls.add("begin");
            return new TxStatus() {
                boolean rollbackOnly;
                @Override public boolean isNewTransaction() { return true; }
                @Override public boolean isReadOnly() { return false; }
                @Override public boolean isRollbackOnly() { return rollbackOnly; }
                @Override public void markRollbackOnly() { rollbackOnly = true; }
                @Override public <R> R resource(Class<R> type) { return null; }
            };
        }
        @Override public void commit(TxStatus status) { calls.add("commit"); }
        @Override public void rollback(TxStatus status) { calls.add("rollback"); }
        @Override public void close() {}
    }

    @Test
    void anHttpExceptionLeavesAsItWasThrownAndRollsBack() {
        Recording manager = new Recording();
        HttpException missing = HttpException.notFound("Project");

        HttpException thrown = assertThrows(HttpException.class, () -> new Tx(manager).run(() -> { throw missing; }));

        assertSame(missing, thrown, "its status must reach the error handler, not become a 500");
        assertEquals(List.of("begin", "rollback"), manager.calls);
    }

    @Test
    void aCheckedExceptionIsWrapped() {
        TxException wrapped = assertThrows(TxException.class, () -> new Tx(new Recording()).call(() -> { throw new IOException("disk"); }));

        assertInstanceOf(IOException.class, wrapped.getCause());
    }

    @Test
    void twoTxOnOneThreadDoNotSeeEachOther() {
        Tx first = new Tx(new Recording());
        Tx second = new Tx(new Recording());

        first.run(() -> {
            assertTrue(first.isActive());
            assertFalse(second.isActive(), "another database's transaction is not this one's");
        });
    }
}
