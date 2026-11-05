package dev.relism.flash.ext.data.jdbc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relism.flash.App;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.flash.ext.data.DataModule;
import dev.relism.flash.ext.data.core.Page;
import dev.relism.flash.ext.data.core.PageRequest;
import dev.relism.flash.ext.data.core.Pager;
import dev.relism.flash.ext.data.core.Query;
import dev.relism.flash.ext.data.core.Sort;
import dev.relism.flash.ext.data.core.Sortable;
import dev.relism.flash.ext.data.core.Spec;
import dev.relism.flash.ext.data.core.SpecBuilder;
import dev.relism.flash.ext.data.core.Tx;
import dev.relism.flash.ext.data.core.TxException;
import dev.relism.flash.ext.jackson.json.JsonHandler;
import dev.relism.flash.ext.jackson.json.JsonModule;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Http1HeaderMap;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keyset pages over a real table: every order, nulls in both directions, a scope, and the HTTP path through serde. */
class PagingTest {

    public record Person(long id, @Sortable String name, @Sortable Integer score,
                         @Sortable @JsonProperty("born_on") LocalDate born) {}

    /** {@code name} lives in {@code full_name}: the column hook at work. */
    public static class People extends JdbcRepository<Person, Long> {
        public People(Tx tx) { super(tx, "person", "id"); }

        @Override protected Person mapRow(ResultSet rs) throws SQLException {
            return new Person(rs.getLong("id"), rs.getString("full_name"), (Integer) rs.getObject("score"),
                    rs.getObject("born", LocalDate.class));
        }

        @Override protected String column(String property) {
            return property.equals("name") ? "full_name" : property;
        }
    }

    public static final class ListPeople extends JsonHandler<PageRequest<Person>, Page<Person>> {
        private final People people;
        public ListPeople(People people) { this.people = people; }

        @Override protected Page<Person> handle(Request request, Response response, PageRequest<Person> page) {
            return people.findPage(page);
        }
    }

    private static final List<Person> ROWS = List.of(
            new Person(1, "ada", 30, LocalDate.of(2000, 1, 1)),
            new Person(2, "bob", null, LocalDate.of(1999, 5, 5)),
            new Person(3, "cid", 10, null),
            new Person(4, "ada", 20, LocalDate.of(2000, 1, 1)),
            new Person(5, "eve", null, LocalDate.of(1990, 2, 2)),
            new Person(6, "bob", 10, null),
            new Person(7, "fay", 30, LocalDate.of(2001, 3, 3)),
            new Person(8, "gus", null, LocalDate.of(1990, 2, 2)),
            new Person(9, "hal", null, null));

    private static final JdbcDataSource SOURCE = new JdbcDataSource();
    private static final People people;

    // Before the extension below starts the app, which happens ahead of any @BeforeAll.
    static {
        SOURCE.setURL("jdbc:h2:mem:paging;DB_CLOSE_DELAY=-1");
        try (Connection c = SOURCE.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table person (id bigint primary key, full_name varchar(20) not null, score int, born date)");
            for (Person p : ROWS)
                s.execute("insert into person values (" + p.id() + ", '" + p.name() + "', " + p.score() + ", "
                        + (p.born() == null ? "null" : "date '" + p.born() + "'") + ")");
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
        people = new People(new Tx(new JdbcTxManager(SOURCE)));
    }

    @RegisterExtension
    static FlashTest api = FlashTest.of(() -> App.create()
            .install(new JsonModule(), new DataModule(new JdbcTxManager(SOURCE)), new OpenApiModule())
            .add(People.class)
            .route(HttpMethod.GET, "/people", ListPeople.class));

    @Test
    void everyOrderVisitsEveryRowOnceWithNullsLast() {
        Pager<Person> pager = Pager.of(Person.class);
        for (int size = 1; size <= 4; size++) {
            for (String sort : pager.sorts()) {
                assertEquals(expected(sort), visit(pager, sort, size, Spec.all()), sort + " by " + size);
            }
        }
    }

    @Test
    void aScopeNarrowsEveryPage() {
        Spec<Person> highIds = SpecBuilder.<Person, Long>field("id").gt(2L);
        // pages [7, 4] [6, 3] [9, 8] [5]: the third ends among the nulls, so the fourth continues there, scoped
        assertEquals(List.of(7L, 4L, 6L, 3L, 9L, 8L, 5L), visit(Pager.of(Person.class), "-score", 2, highIds));
    }

    @Test
    void countsWithACondition() {
        assertEquals(9, people.count());
        assertEquals(4, people.count(SpecBuilder.<Person, Integer>field("score").isNull()));
    }

    @Test
    void aLimitCapsTheRowsRead() {
        assertEquals(List.of(9L, 8L), ids(people.findAll(Query.<Person>all().orderBy(Sort.desc("id")).limit(2))));
        assertEquals(9, people.findAll(Query.<Person>all().limit(0)).size(), "0 is no limit");
        assertEquals(List.of(5L), ids(people.findOne(SpecBuilder.<Person, String>field("full_name").eq("eve")).stream().toList()));
    }

    /** The first page names every column of the pager, so a wrong one fails there whichever sort it belongs to. */
    @Test
    void aWrongColumnFailsOnTheFirstPage() {
        People broken = new People(new Tx(new JdbcTxManager(SOURCE))) {
            @Override protected String column(String property) { return property.equals("score") ? "points" : super.column(property); }
        };
        TxException failure = assertThrows(TxException.class, () -> broken.findPage(Pager.of(Person.class).read(request("sort=name"))));
        assertTrue(failure.getMessage().toUpperCase().contains("POINTS"), failure.getMessage());
    }

    @Test
    void aHandlerTakesItsPageFromTheQueryUnderTheCodecsNames() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Long> visited = new ArrayList<>();
        String after = null;
        do {
            JsonNode page = mapper.readTree(api.get("/people?size=3&sort=-born_on" + (after == null ? "" : "&after=" + after))
                    .expectStatus(200).body());
            page.get("content").forEach(person -> visited.add(person.get("id").asLong()));
            after = page.get("next").isNull() ? null : page.get("next").asText();
        } while (after != null);
        assertEquals(expected("-born"), visited);

        api.get("/people?sort=born").expectStatus(400).expectBodyContains("born_on");
        api.get("/people?size=500").expectStatus(400);
    }

    @Test
    void theDocumentListsThePageParametersAndNoBody() throws Exception {
        JsonNode get = new ObjectMapper().readTree(api.get("/openapi.json").expectStatus(200).body()).at("/paths/~1people/get");
        assertEquals(List.of("size", "sort", "after"), names(get.get("parameters")));
        assertTrue(get.get("parameters").get(1).at("/schema/enum").toString().contains("\"-born_on\""));
        assertTrue(get.get("requestBody") == null, get.toString());
        assertEquals("#/components/schemas/PagePerson", get.at("/responses/200/content/application~1json/schema/$ref").asText());

        JsonNode schema = new ObjectMapper().readTree(api.get("/openapi.json").body()).at("/components/schemas/PagePerson");
        assertEquals("#/components/schemas/Person", schema.at("/properties/content/items/$ref").asText());
        assertTrue(schema.at("/properties/next/nullable").asBoolean());
        assertEquals("[\"content\"]", schema.get("required").toString());
    }

    /** The ids of every page, following each next until there is none. */
    private static List<Long> visit(Pager<Person> pager, String sort, int size, Spec<Person> scope) {
        List<Long> ids = new ArrayList<>();
        String after = null;
        for (int pages = 0; pages < 20; pages++) {
            Page<Person> page = people.findPage(pager.read(request("sort=" + sort + "&size=" + size
                    + (after == null ? "" : "&after=" + after))), scope);
            assertTrue(page.content().size() <= size);
            ids.addAll(ids(page.content()));
            if ((after = page.next()) == null) return ids;
        }
        throw new AssertionError("no last page for " + sort);
    }

    /** What the order means: the value in its direction, nulls after every value, ties by id in the same direction. */
    private static List<Long> expected(String sort) {
        boolean desc = sort.startsWith("-");
        Function<Person, Comparable<Object>> value = switch (desc ? sort.substring(1) : sort) {
            case "id" -> p -> comparable(p.id());
            case "name" -> p -> comparable(p.name());
            case "score" -> p -> comparable(p.score());
            default -> p -> comparable(p.born());
        };
        Comparator<Person> byValue = (a, b) -> value.apply(a).compareTo(value.apply(b));
        Comparator<Person> order = (desc ? byValue.reversed() : byValue)
                .thenComparing(desc ? Comparator.comparingLong(Person::id).reversed() : Comparator.comparingLong(Person::id));
        Comparator<Person> nullsLast = Comparator.<Person, Boolean>comparing(p -> value.apply(p) == null)
                .thenComparing((a, b) -> value.apply(a) == null
                        ? (desc ? Long.compare(b.id(), a.id()) : Long.compare(a.id(), b.id()))
                        : order.compare(a, b));
        return ids(ROWS.stream().sorted(nullsLast).toList());
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> comparable(Object value) {
        return (Comparable<Object>) value;
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).toList();
    }

    private static List<String> names(JsonNode parameters) {
        List<String> names = new ArrayList<>();
        parameters.forEach(p -> names.add(p.get("name").asText()));
        return names;
    }

    private static Request request(String query) {
        return new Request(new RequestLine(HttpMethod.GET, new FastPathViews.StringByteView("/people"),
                new FastPathViews.StringByteView(query), new FastPathViews.StringByteView("HTTP/1.1"),
                new Http1HeaderMap()), new byte[0]);
    }
}
