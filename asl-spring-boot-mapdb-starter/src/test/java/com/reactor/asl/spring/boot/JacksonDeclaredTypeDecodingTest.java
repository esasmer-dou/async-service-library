package com.reactor.asl.spring.boot;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reactor.asl.core.AsyncMethodBinding;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JacksonDeclaredTypeDecodingTest {
    public record Order(String id, int quantity) {
    }

    public interface OrderEvents {
        void publish(List<Order> orders, Map<String, Order> byId, Order single, String note);
    }

    private final JacksonJsonAsyncPayloadCodec codec = new JacksonJsonAsyncPayloadCodec(
            JsonMapper.builder().findAndAddModules().build(),
            getClass().getClassLoader(),
            JacksonAsyncPayloadSchemaRegistry.empty(),
            new AllowlistedJacksonAsyncPayloadTypePolicy(Set.of(Order.class.getName()), Set.of("java.util."))
    );

    @Test
    void restoresGenericElementTypesFromDeclaredParameterTypes() {
        Type[] declared = AsyncMethodBinding.typed(
                OrderEvents.class,
                "publish",
                new Class<?>[]{List.class, Map.class, Order.class, String.class},
                arguments -> { }
        ).parameterTypes();
        assertNotNull(declared);

        List<Order> orders = new ArrayList<>(List.of(new Order("a", 1), new Order("b", 2)));
        Map<String, Order> byId = new LinkedHashMap<>(Map.of("c", new Order("c", 3)));
        byte[] encoded = codec.encode("s", "m", new Object[]{orders, byId, new Order("d", 4), "n"});

        Object[] decoded = codec.decode("s", "m", encoded, declared);

        assertInstanceOf(ArrayList.class, decoded[0]);
        assertInstanceOf(Order.class, ((List<?>) decoded[0]).get(0));
        assertEquals(orders, decoded[0]);
        assertInstanceOf(LinkedHashMap.class, decoded[1]);
        assertEquals(byId, decoded[1]);
        assertEquals(new Order("d", 4), decoded[2]);
        assertEquals("n", decoded[3]);
    }

    @Test
    void immutableListsAreDecodedIntoTheDeclaredCollectionType() {
        Type[] declared = AsyncMethodBinding.typed(
                OrderEvents.class, "publish",
                new Class<?>[]{List.class, Map.class, Order.class, String.class}, arguments -> { }
        ).parameterTypes();
        byte[] encoded = codec.encode("s", "m", new Object[]{List.of(new Order("x", 9)), Map.of(), null, null});

        Object[] decoded = codec.decode("s", "m", encoded, declared);

        assertInstanceOf(Order.class, ((List<?>) decoded[0]).get(0));
    }

    @Test
    void withoutTypeHintsBehaviourIsUnchanged() {
        byte[] encoded = codec.encode("s", "m", new Object[]{new ArrayList<>(List.of(new Order("a", 1)))});
        Object[] decoded = codec.decode("s", "m", encoded);
        // Legacy behaviour: element type is erased.
        assertInstanceOf(Map.class, ((List<?>) decoded[0]).get(0));
    }
}
