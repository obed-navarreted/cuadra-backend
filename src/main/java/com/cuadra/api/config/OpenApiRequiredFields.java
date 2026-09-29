package com.cuadra.api.config;

import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.converter.ModelConverters;
import jakarta.annotation.PostConstruct;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Marca como obligatorios en el contrato OpenAPI los campos que NUNCA son nulos: los de tipo primitivo (`long`, `int`, `boolean`) y los `id`.
 * En esta API las vistas usan primitivos para lo que siempre existe y las entradas usan envoltorios (`Long`, `Boolean`) a propósito, así que
 * el cliente TypeScript generado recibe `totalMinor: number` en las respuestas sin exigir nada de más en las peticiones. Nunca afirma "no nulo" de algo que pueda serlo.
 */
@Component
public class OpenApiRequiredFields implements ModelConverter {
    /** springdoc no registra solo los conversores: se añade al conjunto global (se quita antes por si el contexto de Spring se recrea, como en las pruebas). */
    @PostConstruct
    void register() {
        ModelConverters.getInstance().removeConverter(this);
        ModelConverters.getInstance().addConverter(this);
    }

    @Override
    public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema<?> schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (schema == null || type.getType() == null) return schema;
        Class<?> raw = TypeFactory.defaultInstance().constructType(type.getType()).getRawClass();
        if (!raw.isRecord()) return schema;
        // El esquema devuelto suele ser una referencia (`#/components/schemas/Sales`); lo que hay que completar es el modelo definido con ese nombre.
        String name = schema.getName() != null ? schema.getName() : schema.get$ref() == null ? null : schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
        Schema<?> target = name != null && context.getDefinedModels().get(name) != null ? context.getDefinedModels().get(name) : schema;
        List<String> required = new ArrayList<>(target.getRequired() == null ? List.of() : target.getRequired());
        for (RecordComponent c : raw.getRecordComponents()) {
            if (target.getProperties() == null || !target.getProperties().containsKey(c.getName())) continue;
            boolean never = c.getType().isPrimitive() || c.getName().equals("id");
            if (never) {
                if (!required.contains(c.getName())) required.add(c.getName());
            } else {
                // La API serializa `null` explícito: el contrato lo dice (en OpenAPI 3.1, `type: [X, "null"]` → TypeScript `X | null`).
                // Una referencia a otro esquema no lleva tipo propio, así que solo se marcan los valores simples.
                Schema<?> prop = target.getProperties().get(c.getName());
                if (prop != null && prop.get$ref() == null && prop.getTypes() != null && !prop.getTypes().contains("null")) {
                    java.util.Set<String> types = new java.util.LinkedHashSet<>(prop.getTypes());
                    types.add("null");
                    prop.setTypes(types);
                }
            }
        }
        if (!required.isEmpty()) target.setRequired(required);
        return schema;
    }
}
