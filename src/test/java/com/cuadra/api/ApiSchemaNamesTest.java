package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * El contrato OpenAPI nombra cada esquema por el nombre CORTO de su clase. Dos tipos con el mismo nombre corto (p. ej. `SaleService.PaymentView` y
 * `CreditService.PaymentView`) se pisarían en silencio y el cliente TypeScript quedaría mal tipado. Esta prueba recorre todo lo que la API recibe y
 * devuelve y falla si dos tipos distintos comparten nombre: se arregla con `@Schema(name = "…")` en uno de los dos.
 */
class ApiSchemaNamesTest extends ApiTestBase {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test
    void noTwoApiTypesShareASchemaName() {
        Map<String, Set<Class<?>>> byName = new TreeMap<>();
        Set<Type> seen = new HashSet<>();
        for (HandlerMethod h : mapping.getHandlerMethods().values()) {
            if (!h.getBeanType().getName().startsWith("com.cuadra.api")) continue;
            Method m = h.getMethod();
            visit(m.getGenericReturnType(), byName, seen);
            for (var p : m.getParameters()) if (p.isAnnotationPresent(RequestBody.class)) visit(p.getParameterizedType(), byName, seen);
        }
        assertTrue(byName.size() > 40, "La prueba debe recorrer los tipos de la API (encontró " + byName.size() + ")");
        List<String> clashes = new ArrayList<>();
        byName.forEach((name, classes) -> {
            if (classes.size() > 1) clashes.add(name + " ← " + classes.stream().map(Class::getName).sorted().toList());
        });
        assertTrue(clashes.isEmpty(), "Tipos de la API con el mismo nombre de esquema:\n  " + String.join("\n  ", clashes));
    }

    private static void visit(Type t, Map<String, Set<Class<?>>> out, Set<Type> seen) {
        if (t == null || !seen.add(t)) return;
        if (t instanceof ParameterizedType p) {
            visit(p.getRawType(), out, seen);
            for (Type a : p.getActualTypeArguments()) visit(a, out, seen);
        } else if (t instanceof GenericArrayType g) {
            visit(g.getGenericComponentType(), out, seen);
        } else if (t instanceof WildcardType w) {
            for (Type b : w.getUpperBounds()) visit(b, out, seen);
        } else if (t instanceof Class<?> c) {
            if (c.isArray()) visit(c.getComponentType(), out, seen);
            if (!c.getName().startsWith("com.cuadra.api") || !c.isRecord()) return;
            // Un `@Schema(name = "…")` en la clase cambia su nombre en el contrato.
            var schema = c.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
            String name = schema != null && !schema.name().isEmpty() ? schema.name() : c.getSimpleName();
            out.computeIfAbsent(name, k -> new HashSet<>()).add(c);
            for (RecordComponent rc : c.getRecordComponents()) visit(rc.getGenericType(), out, seen);
        }
    }
}
