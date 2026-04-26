package dev.relism.flash.ext.view.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ViewModelTest {

    @Test
    void ofWithAndWithAll_populateModel() {
        ViewModel model = ViewModel.of("a", 1)
                .with("b", "x")
                .withAll(Map.of("c", true));

        assertEquals(1, model.toMap().get("a"));
        assertEquals("x", model.toMap().get("b"));
        assertEquals(true, model.toMap().get("c"));
    }

    @Test
    void merge_otherWinsOnCollision() {
        ViewModel global = ViewModel.of("nav", "global").with("app", "flash");
        ViewModel local = ViewModel.of("nav", "local");

        ViewModel merged = global.merge(local);

        assertEquals("local", merged.toMap().get("nav"));
        assertEquals("flash", merged.toMap().get("app"));
    }

    @Test
    void toMap_isReadOnly() {
        ViewModel model = ViewModel.of("k", "v");
        Map<String, Object> map = model.toMap();

        assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
    }

    @Test
    void nestedViewModel_isUnwrappedToMapRecursively() {
        ViewModel nested = ViewModel.of("name", "Flash");
        ViewModel root = ViewModel.of("app", nested)
                .with("list", List.of(ViewModel.of("n", 1), ViewModel.of("n", 2)));

        Object app = root.toMap().get("app");
        Object list = root.toMap().get("list");

        assertTrue(app instanceof Map<?, ?>);
        assertEquals("Flash", ((Map<?, ?>) app).get("name"));
        assertTrue(list instanceof List<?>);
        assertTrue(((List<?>) list).getFirst() instanceof Map<?, ?>);
    }

    @Test
    void with_nullKey_throws() {
        ViewModel model = ViewModel.empty();
        assertThrows(NullPointerException.class, () -> model.with(null, 1));
    }

    @Test
    void withAll_nullOrEmpty_noop() {
        ViewModel model = ViewModel.of("a", 1);

        model.withAll(null);
        model.withAll(Map.of());

        assertEquals(1, model.toMap().size());
        assertEquals(1, model.toMap().get("a"));
    }

    @Test
    void merge_nullOther_returnsCopy() {
        ViewModel base = ViewModel.of("a", 1);

        ViewModel merged = base.merge(null);

        assertNotSame(base, merged);
        assertEquals(1, merged.toMap().get("a"));
    }
}
