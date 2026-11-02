package studio.qsa3ed.qcore.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Central factory for the JSON and YAML mappers.
 *
 * <p>One configuration is used everywhere so that REST payloads, Redis
 * messages and configuration files always agree on their representation.
 * Mappers are immutable after configuration and therefore thread safe.</p>
 */
public final class Json {

    private static final ObjectMapper JSON = configure(new ObjectMapper());
    private static final ObjectMapper YAML = configure(new ObjectMapper(new YAMLFactory()));

    private Json() {
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
        return mapper
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /** @return the shared JSON mapper. */
    public static ObjectMapper mapper() {
        return JSON;
    }

    /** @return the shared YAML mapper. */
    public static ObjectMapper yamlMapper() {
        return YAML;
    }
}
