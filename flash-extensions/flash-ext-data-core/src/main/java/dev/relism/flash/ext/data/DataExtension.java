package dev.relism.flash.ext.data;

import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxDefinition;
import dev.relism.flash.ext.data.core.TxManager;
import dev.relism.flash.ext.data.core.TransactionPropagation;
import dev.relism.flash.extension.ExtensionPhase;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.routing.Middleware;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Objects;

public final class DataExtension implements FlashExtension {
    private final TxManager txManager;
    private final Tx tx;

    public DataExtension(TxManager txManager) {
        this.txManager = Objects.requireNonNull(txManager);
        this.tx = new Tx(txManager);
    }

    @Override
    public void provide(FlashContext ctx) {
        ctx.provide(Tx.class, tx);
        ctx.provide(TxManager.class, txManager);
        ctx.addAnnotationProcessor(handlerClass -> {
            Transactional ann = handlerClass.getAnnotation(Transactional.class);
            if (ann == null) {
                return List.of();
            }
            TxDefinition definition = TxDefinition.DEFAULTS
                    .withPropagation(mapTxType(ann.value()));
            Middleware middleware = next -> (req, res) -> {
                return tx.call(definition, () -> next.handle(req, res));
            };
            return List.of(middleware);
        });
    }

    @Override
    public int priority() {
        return ExtensionPhase.EARLY.value;
    }

    private TransactionPropagation mapTxType(Transactional.TxType txType) {
        return switch (txType) {
            case REQUIRED -> TransactionPropagation.REQUIRED;
            case REQUIRES_NEW -> TransactionPropagation.REQUIRES_NEW;
            case SUPPORTS -> TransactionPropagation.SUPPORTS;
            case MANDATORY -> TransactionPropagation.MANDATORY;
            case NOT_SUPPORTED, NEVER -> TransactionPropagation.NOT_SUPPORTED;
        };
    }
}
