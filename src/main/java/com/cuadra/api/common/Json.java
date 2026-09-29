package com.cuadra.api.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Columnas de texto que guardan JSON pequeño (módulos, vistas de la caja). */
@Component
public class Json {
    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Boolean> readFlags(String json) {
        return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Boolean>>() {});
    }

    public List<String> readList(String json) {
        return mapper.readValue(json, new TypeReference<List<String>>() {});
    }
}
