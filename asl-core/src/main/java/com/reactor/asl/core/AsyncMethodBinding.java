package com.reactor.asl.core;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;

@FunctionalInterface
public interface AsyncMethodBinding {
    void invoke(Object[] arguments) throws Throwable;

    /**
     * Declared (generic) parameter types of the bound method, or {@code null} when unknown.
     * Codecs use them to restore element types such as {@code List<Order>} that are erased from
     * runtime classes.
     */
    default Type[] parameterTypes() {
        return null;
    }

    /** Binding that carries the declared parameter types. */
    static AsyncMethodBinding typed(Type[] parameterTypes, AsyncMethodBinding delegate) {
        Objects.requireNonNull(delegate, "delegate");
        Type[] types = parameterTypes == null ? null : parameterTypes.clone();
        return new AsyncMethodBinding() {
            @Override
            public void invoke(Object[] arguments) throws Throwable {
                delegate.invoke(arguments);
            }

            @Override
            public Type[] parameterTypes() {
                return types == null ? null : types.clone();
            }
        };
    }

    /**
     * Binding for generated wrappers: resolves the generic parameter types of
     * {@code owner.methodName(erasedParameterTypes)} once, at registration time. Dispatch stays
     * reflection-free. When the method cannot be resolved the binding works without type hints.
     */
    static AsyncMethodBinding typed(
            Class<?> owner,
            String methodName,
            Class<?>[] erasedParameterTypes,
            AsyncMethodBinding delegate
    ) {
        Type[] types;
        try {
            Method method = owner.getMethod(methodName, erasedParameterTypes);
            types = method.getGenericParameterTypes();
        } catch (NoSuchMethodException | SecurityException unresolved) {
            types = null;
        }
        return typed(types, delegate);
    }
}
