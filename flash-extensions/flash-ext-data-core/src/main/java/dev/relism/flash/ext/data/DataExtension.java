package dev.relism.flash.ext.data;

import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxDefinition;
import dev.relism.flash.ext.data.core.TxManager;
import dev.relism.flash.ext.data.core.TransactionPropagation;
import dev.relism.flash.extension.ExtensionPhase;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.routing.Middleware;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Objects;

public final class DataExtension implements FlashExtension {
    private final TxManager txManager;

    public DataExtension(TxManager txManager) {
        this.txManager = Objects.requireNonNull(txManager);
    }

    @Override
    public void provide(FlashContext ctx) {
        Tx.init(txManager);
        ctx.provide(TxManager.class, txManager);
        ctx.addAnnotationProcessor(handlerClass -> {
            Transactional ann = handlerClass.getAnnotation(Transactional.class);
            if (ann == null) {
                return List.of();
            }
            TxDefinition definition = TxDefinition.DEFAULTS
                    .withPropagation(mapTxType(ann.value()));
            Middleware middleware = next -> (req, res) -> {
                return Tx.call(definition, () -> next.handle(req, res));
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
            case REQUIRED, SUPPORTS -> TransactionPropagation.REQUIRED;
            case REQUIRES_NEW -> TransactionPropagation.REQUIRES_NEW;
            case MANDATORY -> TransactionPropagation.MANDATORY;
            case NOT_SUPPORTED, NEVER -> TransactionPropagation.NOT_SUPPORTED;
        };
    }
}
