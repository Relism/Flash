package dev.relism.flash.ext.data.core;

import java.io.Serializable;

/** Creates the backend-specific, stateless repository for one entity type. */
@FunctionalInterface
public interface RepositoryFactory {
    <T, ID extends Serializable> Repository<T, ID> create(Tx tx, Class<T> type);
}
