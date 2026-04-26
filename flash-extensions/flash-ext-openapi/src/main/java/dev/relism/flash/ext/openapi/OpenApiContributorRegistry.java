package dev.relism.flash.ext.openapi;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OpenApiContributorRegistry {
    private final List<OpenApiContributor> contributors = new CopyOnWriteArrayList<>();

    public void add(OpenApiContributor contributor) {
        contributors.add(contributor);
    }

    public List<OpenApiContributor> contributors() {
        return Collections.unmodifiableList(contributors);
    }
}
