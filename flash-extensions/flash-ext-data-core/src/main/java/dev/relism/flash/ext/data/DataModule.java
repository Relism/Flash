package dev.relism.flash.ext.data;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.data.core.Data;
import dev.relism.flash.ext.data.core.TransactionPropagation;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxDefinition;
import dev.relism.flash.ext.data.core.TxException;
import dev.relism.flash.ext.data.core.TxManager;
import dev.relism.flash.http.AnsweredFailure;
import dev.relism.flash.http.Exchange;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.RouteHook;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Transactions for an app: its {@link TxManager}, the {@link Tx} over it, {@link Data} when there is
 * one, and {@link Transactional} on a handler class. The manager is closed with the app, its
 * connection pool included.
 *
 * <pre>{@code
 * app.install(new DataModule(txManager, data));
 * }</pre>
 */
public final class DataModule implements Module {

    private static final MiddlewareKey TRANSACTION = MiddlewareKey.of("flash.data.transaction");

    private final TxManager txManager;
    private final Tx tx;
    private final Data data;

    public DataModule(TxManager txManager) {
        this(txManager, null);
    }

    public DataModule(TxManager txManager, Data data) {
        this.txManager = Objects.requireNonNull(txManager);
        this.data = data;
        this.tx = data != null ? data.tx() : new Tx(txManager);
    }

    @Override
    public void install(App app) {
        app.add(TxManager.class, txManager).add(Tx.class, tx).add(RouteHook.class, new RouteHook() {
            @Override public List<MiddlewareNode> middleware(Class<?> handler) { return transactional(handler); }
        });
        if (data != null) app.add(Data.class, data);
    }

    /** {@link Transactional} on the handler class: the whole request in one transaction, as it says. */
    private List<MiddlewareNode> transactional(Class<?> handler) {
        Transactional declared = handler.getAnnotation(Transactional.class);
        if (declared == null) return List.of();
        Transactional.TxType type = declared.value();
        TxDefinition definition = TxDefinition.DEFAULTS.withPropagation(switch (type) {
            case REQUIRED -> TransactionPropagation.REQUIRED;
            case REQUIRES_NEW -> TransactionPropagation.REQUIRES_NEW;
            case SUPPORTS -> TransactionPropagation.SUPPORTS;
            case MANDATORY -> TransactionPropagation.MANDATORY;
            case NOT_SUPPORTED, NEVER -> TransactionPropagation.NOT_SUPPORTED;
        });
        Class<?>[] commitDespite = declared.dontRollbackOn();
        Exchange.Around inTransaction = work -> {
            if (type == Transactional.TxType.NEVER && tx.isActive())
                throw new TxException("NEVER: " + handler.getName() + " runs inside a transaction");
            Exception[] kept = new Exception[1];
            tx.call(definition, () -> {
                try {
                    return work.call();
                } catch (Exception failure) {
                    // dontRollbackOn: the transaction commits, and the exception still reaches the caller.
                    Throwable reason = failure instanceof AnsweredFailure answered ? answered.getCause() : failure;
                    for (Class<?> exempt : commitDespite) {
                        if (exempt.isInstance(reason)) {
                            kept[0] = failure;
                            return null;
                        }
                    }
                    throw failure;
                }
            });
            if (kept[0] != null) throw kept[0];
        };
        Middleware middleware = (exchange, next) -> {
            inTransaction.run(() -> {
                next.handle(exchange);
                return null;
            });
            // a duplex handler or a streaming body runs after the chain, in a transaction of its own
            if (exchange.deferred()) exchange.around(inTransaction);
        };
        return List.of(MiddlewareNode.of(TRANSACTION, middleware));
    }
}
