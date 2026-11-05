package dev.relism.flash.ext.validation;

import dev.relism.flash.App;
import dev.relism.flash.ext.serde.Check;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each decoded type goes to the one engine that checks it, whatever the codec. */
class ValidationCheckTest {

    record Order(String id) {}
    record Message(String text) {}

    /** Checks only {@code type}, recording what it was handed. */
    record Engine(Class<?> type, List<Object> seen) implements Validation {
        Engine(Class<?> type) { this(type, new ArrayList<>()); }
        @Override public boolean checks(Class<?> checked) { return checked == type; }
        @Override public void verify(Object value) { seen.add(value); }
    }

    static final class Everything implements Validation {
        @Override public void verify(Object value) {}
    }

    private static Check check(Validation... engines) {
        FlashTest app = FlashTest.of(() -> {
            App created = App.create();
            for (Validation engine : engines) created.install(Validation.module(engine));
            return created;
        });
        List<Check> checks = app.services().all(Check.class);
        assertEquals(1, checks.size(), "one check, however many engines");
        return checks.get(0);
    }

    @Test
    void eachTypeGoesToTheEngineThatChecksIt() {
        Engine orders = new Engine(Order.class), messages = new Engine(Message.class);
        Check check = check(orders, messages);

        check.of(Order.class).accept(new Order("o1"));
        check.of(Message.class).accept(new Message("hi"));

        assertEquals(List.of(new Order("o1")), orders.seen());
        assertEquals(List.of(new Message("hi")), messages.seen());
    }

    @Test
    void aTypeNoEngineChecksIsNotChecked() {
        assertNull(check(new Engine(Order.class)).of(String.class));
    }

    @Test
    void aListIsCheckedByTheEngineOfItsElement() throws Exception {
        Engine orders = new Engine(Order.class);
        Type listOfOrders = ValidationCheckTest.class.getDeclaredMethod("orders").getGenericReturnType();

        Consumer<Object> checked = check(orders).of(listOfOrders);
        checked.accept(List.of(new Order("o1")));

        assertEquals(List.of(List.of(new Order("o1"))), orders.seen());
    }

    @Test
    void anArrayReachesTheEngineOfItsElementAsAList() {
        Engine orders = new Engine(Order.class);

        check(orders).of(Order[].class).accept(new Order[] {new Order("o1"), new Order("o2")});

        assertEquals(List.of(List.of(new Order("o1"), new Order("o2"))), orders.seen());
    }

    @Test
    void twoEnginesForOneTypeFailNamingBoth() {
        Check check = check(new Engine(Order.class), new Everything());

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> check.of(Order.class));

        assertTrue(refused.getMessage().contains("Engine") && refused.getMessage().contains("Everything"), refused.getMessage());
    }

    @SuppressWarnings("unused")
    private static List<Order> orders() {
        return List.of();
    }
}
