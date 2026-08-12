package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.Data;
import dev.relism.flash.ext.data.core.Repository;
import dev.relism.flash.ext.data.core.RepositoryFactory;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxManager;
import java.io.Serializable;

/** Hibernate-backed {@link Data} factory. */
public final class HibernateData {
    private HibernateData() {}

    private static final RepositoryFactory REPOSITORIES = new RepositoryFactory() {
        @Override
        public <T, ID extends Serializable> Repository<T, ID> create(Tx tx, Class<T> type) {
            return new HibernateRepository<T, ID>(tx, type) {};
        }
    };

    public static Data create(TxManager manager) {
        Tx tx = new Tx(manager);
        return new Data(tx, REPOSITORIES);
    }
}
