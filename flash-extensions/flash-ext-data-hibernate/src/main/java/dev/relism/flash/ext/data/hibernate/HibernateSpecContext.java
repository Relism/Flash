package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.SpecContext;
import jakarta.persistence.TypedQuery;
import org.hibernate.query.MutationQuery;

import java.util.LinkedHashMap;
import java.util.Map;

final class HibernateSpecContext implements SpecContext {
    private final Map<String, Object> params = new LinkedHashMap<>();
    private int counter;

    @Override
    public String bind(Object value) {
        String name = "p" + (++counter);
        params.put(name, value);
        return ":" + name;
    }

    void applyParameters(TypedQuery<?> query) {
        params.forEach(query::setParameter);
    }

    void applyParameters(MutationQuery query) {
        params.forEach(query::setParameter);
    }
}
