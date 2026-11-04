package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.bytes.FastPathViews;
import dev.relism.flash.ext.data.core.Data;
import dev.relism.flash.ext.data.core.Page;
import dev.relism.flash.ext.data.core.Pager;
import dev.relism.flash.ext.data.core.Repository;
import dev.relism.flash.ext.data.core.Sortable;
import dev.relism.flash.ext.data.core.SpecBuilder;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keyset pages in HQL: the entity's {@code @Id} is the tie-breaker, and a nullable column pages its nulls last. */
class PagingTest {

    @Entity(name = "Item")
    public static class Item {
        @Id Long code;
        @Sortable String label;
        @Sortable Integer priority;

        protected Item() {}

        Item(long code, String label, Integer priority) {
            this.code = code;
            this.label = label;
            this.priority = priority;
        }
    }

    private static SessionFactory sessions;
    private static HibernateTxManager manager;
    private static Repository<Item, Long> items;
    private static final Pager<Item> PAGER = Pager.of(Item.class);

    @BeforeAll
    static void store() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:paging-hibernate;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.hbm2ddl.auto", "create-drop")
                .build();
        sessions = new MetadataSources(registry).addAnnotatedClass(Item.class).buildMetadata().buildSessionFactory();
        manager = new HibernateTxManager(sessions);
        Data data = HibernateData.create(manager);
        items = data.repository(Item.class);
        data.write(() -> {
            items.save(new Item(1, "b", 2));
            items.save(new Item(2, "a", null));
            items.save(new Item(3, "c", 1));
            items.save(new Item(4, "a", 2));
            items.save(new Item(5, "d", null));
        });
    }

    @AfterAll
    static void close() {
        manager.close();
    }

    @Test
    void theIdIsTheAnnotatedField() {
        assertEquals(List.of("code", "-code", "label", "-label", "priority", "-priority"), PAGER.sorts());
    }

    @Test
    void pagesFollowTheirOrderWithNullsLast() {
        assertEquals(List.of(3L, 1L, 4L, 2L, 5L), visit("priority", 2));
        assertEquals(List.of(4L, 1L, 3L, 5L, 2L), visit("-priority", 2));
        assertEquals(List.of(2L, 4L, 1L, 3L, 5L), visit("label", 3));
        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), visit("-code", 1));
    }

    @Test
    void aScopeAndACountSpeakHql() {
        Page<Item> page = items.findPage(PAGER.read(request("sort=priority")), SpecBuilder.<Item, String>field("label").eq("a"));
        assertEquals(List.of(4L, 2L), page.content().stream().map(i -> i.code).toList());
        assertEquals(2, items.count(SpecBuilder.<Item, Integer>field("priority").isNull()));
    }

    private static List<Long> visit(String sort, int size) {
        List<Long> codes = new ArrayList<>();
        String after = null;
        do {
            Page<Item> page = items.findPage(PAGER.read(request("sort=" + sort + "&size=" + size
                    + (after == null ? "" : "&after=" + after))));
            page.content().forEach(item -> codes.add(item.code));
            after = page.next();
        } while (after != null);
        return codes;
    }

    private static Request request(String query) {
        return new Request(new RequestLine(HttpMethod.GET, new FastPathViews.StringByteView("/items"),
                new FastPathViews.StringByteView(query), new FastPathViews.StringByteView("HTTP/1.1"),
                new Http1HeaderMap()), new byte[0]);
    }
}
