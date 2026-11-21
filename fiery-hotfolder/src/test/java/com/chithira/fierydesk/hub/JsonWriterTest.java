package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.fiery.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JSON written by the desktop must read back the same with the desktop's own parser. */
class JsonWriterTest {

    @Test
    void writesNestedValuesWithEscaping() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "Anu \"Weddings\"\n");
        value.put("count", 3);
        value.put("ok", true);
        value.put("missing", null);
        value.put("items", Arrays.asList(1, "two", null));

        String json = JsonWriter.write(value);

        assertEquals("{\"name\":\"Anu \\\"Weddings\\\"\\n\",\"count\":3,\"ok\":true,\"missing\":null,\"items\":[1,\"two\",null]}", json);
        Map<?, ?> back = (Map<?, ?>) JsonParser.parse(json);
        assertEquals("Anu \"Weddings\"\n", back.get("name"));
        assertEquals(List.of("1", "two"), ((List<?>) back.get("items")).subList(0, 2));
    }
}
