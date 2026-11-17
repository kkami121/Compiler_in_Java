package io.github.kkami121.codeanalyzer.json;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void roundTrip() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("s", "a\"b\\c\n\u0001한글");
        map.put("n", 42L);
        map.put("d", 1.5);
        map.put("b", true);
        map.put("z", null);
        map.put("list", Arrays.asList(1L, "x", Arrays.asList()));
        String text = Json.write(map);
        assertEquals(map, Json.parse(text));
        assertEquals(map, Json.parse(Json.writePretty(map)));
    }

    @Test
    void numbersAreWrittenPlainly() {
        assertEquals("[1220.0,0.12,0.0000001,null,7]",
                Json.write(Arrays.asList(1220.0, 0.12, 0.0000001, Double.NaN, 7L)));
    }

    @Test
    void parsesEscapes() {
        assertEquals("A\t/", Json.parse("\"\\u0041\\t\\/\""));
    }

    @Test
    void rejectsInvalidJson() {
        for (String bad : Arrays.asList("", "{", "{\"a\" 1}", "[1,]", "tru", "\"open", "1 2", "{'a':1}", "-")) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void rejectsTooDeepNesting() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            deep.append('[');
        }
        assertThrows(Json.JsonException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void parsesNestedStructures() {
        Object value = Json.parse("{\"files\": [{\"name\": \"a.py\"}], \"x\": -1.5e2}");
        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals(-150.0, map.get("x"));
        assertEquals("a.py", ((Map<?, ?>) ((List<?>) map.get("files")).get(0)).get("name"));
    }
}
