package dev.relism.flash;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicesTest {

    static final List<String> closed = new ArrayList<>();

    public record Clock(long now) {}

    public static final class Repo implements AutoCloseable {
        final Clock clock;
        public Repo(Clock clock) { this.clock = clock; }
        @Override public void close() { closed.add("repo"); }
    }

    public static final class Users implements AutoCloseable {
        final Repo repo;
        final Optional<String> motto;
        final List<Plugin> plugins;
        public Users(Repo repo, Optional<String> motto, List<Plugin> plugins) {
            this.repo = repo;
            this.motto = motto;
            this.plugins = plugins;
        }
        @Override public void close() { closed.add("users"); }
    }

    public interface Plugin {}
    public static final class First implements Plugin {}
    public static final class Second implements Plugin {}

    public static final class Egg { public Egg(Chicken chicken) {} }
    public static final class Chicken { public Chicken(Egg egg) {} }

    private static Services started(App app) {
        app.compile(ServerConfig.builder().build());
        return app.services();
    }

    @Test
    void theServerConfigIsAService() {
        ServerConfig config = ServerConfig.builder().dev(true).build();
        App app = App.create();
        app.compile(config);
        assertSame(config, app.services().get(ServerConfig.class));
    }

    @Test
    void aClassIsBuiltFromWhatItsConstructorNamesWhateverTheOrderThingsWereAdded() {
        Services services = started(App.create()
                .add(Users.class)
                .add(Plugin.class, Second.class)
                .add(Repo.class)
                .add(Plugin.class, First.class)
                .add(Clock.class, new Clock(42)));

        Users users = services.get(Users.class);
        assertSame(services.get(Repo.class), users.repo);
        assertEquals(42, users.repo.clock.now());
        assertTrue(users.motto.isEmpty(), "an Optional of nothing added is empty");
        assertEquals(List.of(Second.class, First.class), users.plugins.stream().map(Object::getClass).toList(),
                "a List is everything added, in the order it was added");
    }

    @Test
    void addingTheSameClassTwiceAddsItOnce() {
        Services services = started(App.create().add(Plugin.class, First.class).add(Plugin.class, First.class));

        assertEquals(1, services.all(Plugin.class).size());
    }

    @Test
    void getRefusesATypeAddedTwiceNamingBoth() {
        Services services = started(App.create().add(Plugin.class, First.class).add(Plugin.class, Second.class));

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> services.get(Plugin.class));
        assertTrue(refused.getMessage().contains(First.class.getName()) && refused.getMessage().contains(Second.class.getName()),
                refused.getMessage());
    }

    @Test
    void aMissingServiceFailsTheBootNamingWhoNeededIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> started(App.create().add(Repo.class)));

        assertTrue(refused.getMessage().contains(Clock.class.getName()) && refused.getMessage().contains(Repo.class.getName()),
                refused.getMessage());
    }

    @Test
    void aCycleFailsTheBootWithItsPath() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> started(App.create().add(Egg.class).add(Chicken.class)));

        assertTrue(refused.getMessage().contains("Egg -> Chicken -> Egg"), refused.getMessage());
    }

    @Test
    void replaceSwapsTheBindingForAFake() {
        Clock fake = new Clock(7);
        Services services = started(App.create().add(Clock.class, new Clock(1)).replace(Clock.class, fake));

        assertSame(fake, services.get(Clock.class));
    }

    @Test
    void servicesAreNotReadBeforeTheServerStarts() {
        App app = App.create().add(Clock.class, new Clock(1));

        assertThrows(IllegalStateException.class, () -> app.services().get(Clock.class));
    }

    @Test
    void closingClosesWhatWasBuiltInReverseOrder() {
        closed.clear();
        Services services = started(App.create().add(Clock.class, new Clock(1)).add(Users.class).add(Repo.class));
        services.get(Users.class);

        services.close();

        assertEquals(List.of("users", "repo"), closed);
    }
}
