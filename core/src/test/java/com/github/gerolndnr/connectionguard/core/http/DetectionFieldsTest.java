package com.github.gerolndnr.connectionguard.core.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DetectionFieldsTest {
    private static JsonObject field(String value) {
        return JsonParser.parseString("{\"asn\":" + value + "}").getAsJsonObject();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"AS0\"", "\"0\"", "0", "\" AS0 \""})
    void reservedZeroMeansAbsentAsn(String value) {
        assertNull(DetectionFields.asn(field(value), "asn", false));
    }

    @Test
    void reservedZeroWithOrganizationSuffixMeansAbsentAsn() {
        assertNull(DetectionFields.asn(field("\"AS0 Unknown\""), "asn", true));
        assertThrows(IllegalArgumentException.class,
                () -> DetectionFields.asn(field("\"AS0 Unknown\""), "asn", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"AS-1\"", "\"ASabc\"", "\"AS4294967296\"", "-1", "4294967296", "\"00\"", "0.0", "true", "[]", "{}"})
    void malformedAsnsRemainErrors(String value) {
        assertThrows(IllegalArgumentException.class,
                () -> DetectionFields.asn(field(value), "asn", false));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "4294967295,4294967295", "\"AS15169\",15169"})
    void realAsnsRetainTheirValues(String value, long expected) {
        assertEquals(Long.valueOf(expected), DetectionFields.asn(field(value), "asn", false));
    }
}
