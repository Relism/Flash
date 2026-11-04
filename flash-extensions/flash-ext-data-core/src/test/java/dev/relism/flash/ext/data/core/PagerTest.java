package dev.relism.flash.ext.data.core;

import dev.relism.flash.bytes.FastPathViews;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagerTest {

    record User(long id, @Sortable String name, @Sortable Instant lastLogin, String secret) {}

    private final Pager<User> pager = Pager.of(User.class);

    @Test
    void noParametersIsTheFirstPageAlreadyBuilt() {
        PageRequest<User> first = pager.read(request(null));
        assertSame(first, pager.read(request("")));
        assertSame(first, pager.read(request("size=&sort=&after=&other=1")));
        assertEquals(Pager.DEFAULT_SIZE, first.size);
        assertEquals("id", sort(first));
        assertNull(first.after);
    }

    @Test
    void sizeAndSortAreReadFromTheQuery() {
        PageRequest<User> page = pager.read(request("size=5&sort=-lastLogin"));
        assertEquals(5, page.size);
        assertEquals("-lastLogin", sort(page));
        assertEquals("name", sort(pager.read(request("sort=name&sort=-id"))), "the first value counts");
        assertEquals("-name", sort(pager.read(request("sort=%2Dname"))), "an escaped value is decoded");
        assertEquals("-name", sort(pager.read(request("sort=&sort=%2Dname"))), "an empty value does not count");
    }

    @Test
    void whatIsNotAPageIsABadRequest() {
        for (String query : List.of("size=0", "size=101", "size=ten", "size=-1", "size=0010"))
            assertEquals(400, assertThrows(HttpException.class, () -> pager.read(request(query))).status(), query);
        HttpException sort = assertThrows(HttpException.class, () -> pager.read(request("sort=secret")));
        assertEquals("sort must be one of id, name, lastLogin, with - to reverse it", sort.getMessage());
        assertThrows(HttpException.class, () -> pager.read(request("after=not-a-token")));
    }

    @Test
    void theTokenOfAPageCarriesItsLastItemAndItsSort() {
        User last = new User(7, "ada", Instant.ofEpochSecond(1_700_000_000, 5), "x");
        PageRequest<User> next = pager.read(request("sort=-lastLogin&after=" + pager.token(5, last)));
        assertEquals("-lastLogin", sort(next));
        assertArrayEquals(new Object[] {last.lastLogin(), 7L}, next.after);

        User unseen = new User(9, "bob", null, "x");
        assertArrayEquals(new Object[] {null, 9L}, pager.read(request("sort=-lastLogin&after=" + pager.token(5, unseen))).after);
        assertArrayEquals(new Object[] {7L, 7L}, pager.read(request("after=" + pager.token(0, last))).after);
        assertArrayEquals(new Object[] {7L, 7L}, pager.read(request("after=&after=" + pager.token(0, last))).after);

        HttpException other = assertThrows(HttpException.class,
                () -> pager.read(request("sort=name&after=" + pager.token(5, last))));
        assertEquals("after belongs to a page with another sort", other.getMessage());
    }

    @Test
    void namesComeFromTheFunctionGiven() {
        Pager<User> snake = Pager.of(User.class, field -> field.getName().equals("lastLogin") ? "last_login" : field.getName());
        assertEquals(List.of("id", "-id", "name", "-name", "last_login", "-last_login"), snake.sorts());
        assertEquals("-last_login", sort(snake.read(request("sort=-last_login"))));
    }

    @Test
    void aPageIsTheRowsItHoldsAndTheTokenOfTheLast() {
        PageRequest<User> request = pager.read(request("size=2"));
        List<User> rows = List.of(user(1), user(2), user(3));
        Page<User> page = request.page(rows);
        assertEquals(List.of(user(1), user(2)), page.content());
        assertEquals(pager.token(0, user(2)), page.next());
        assertNull(request.page(rows.subList(0, 2)).next(), "no row beyond the page: the last one");
        assertEquals(Map.of(1L, "1"), Map.of(page.map(User::id).content().get(0), page.content().get(0).name()));
    }

    record NoId(long key, @Sortable String name) {}
    record Unsupported(long id, @Sortable Map<String, String> tags) {}
    record Twice(long id, @Sortable String name, @Sortable String other) {}

    @Test
    void aTypeThatCannotBePagedFailsWhenItsPagerIsBuilt() {
        assertTrue(assertThrows(IllegalStateException.class, () -> Pager.of(NoId.class)).getMessage().contains("no id"));
        assertTrue(assertThrows(IllegalStateException.class, () -> Pager.of(Unsupported.class)).getMessage().contains("tags"));
        assertTrue(assertThrows(IllegalStateException.class, () -> Pager.of(Twice.class, f -> f.getName().equals("id") ? "id" : "same"))
                .getMessage().contains("two sortable fields named same"));
    }

    record Keys(UUID id, @Sortable BigDecimal amount, @Sortable OffsetDateTime at, @Sortable double ratio,
                @Sortable LocalTime time, @Sortable boolean flag) {}

    @Test
    void everyKeyTypeComesBackFromItsTokenAsItWas() {
        Pager<Keys> keys = Pager.of(Keys.class);
        Keys last = new Keys(UUID.randomUUID(), new BigDecimal("12.3400"), OffsetDateTime.parse("2026-11-04T10:15:30.000000001+02:00"),
                0.1 + 0.2, LocalTime.of(10, 15), true);
        assertArrayEquals(new Object[] {last.amount(), last.id()}, keys.read(request("sort=amount&after=" + keys.token(2, last))).after);
        assertArrayEquals(new Object[] {last.at(), last.id()}, keys.read(request("sort=at&after=" + keys.token(4, last))).after);
        assertArrayEquals(new Object[] {last.ratio(), last.id()}, keys.read(request("sort=ratio&after=" + keys.token(6, last))).after);
        assertArrayEquals(new Object[] {last.time(), last.id()}, keys.read(request("sort=time&after=" + keys.token(8, last))).after);
        assertArrayEquals(new Object[] {true, last.id()}, keys.read(request("sort=flag&after=" + keys.token(10, last))).after);
    }

    private static String sort(PageRequest<?> request) {
        return ((request.sort & 1) == 1 ? "-" : "") + request.pager.keys[request.sort >> 1].wire;
    }

    private static User user(long id) {
        return new User(id, String.valueOf(id), null, null);
    }

    private static Request request(String query) {
        return new Request(new RequestLine(HttpMethod.GET, new FastPathViews.StringByteView("/users"),
                query == null ? null : new FastPathViews.StringByteView(query),
                new FastPathViews.StringByteView("HTTP/1.1"), new Http1HeaderMap()), new byte[0]);
    }
}
