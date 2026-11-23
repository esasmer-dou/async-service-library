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

    /**
     * {@code true} for a method that returns {@code CompletableFuture<T>}: the engine calls
     * {@link #invokeForResult(Object[])} and waits for the returned future before the invocation counts as done.
     */
    default boolean returnsFuture() {
        return false;
    }

    /**
     * Runs the method and returns what it returned: the method's {@code CompletableFuture} for future bindings,
     * {@code null} for {@code void} methods.
     */
    default Object invokeForResult(Object[] arguments) throws Throwable {
        invoke(arguments);
        return null;
    }

    /** Call of a governed method that returns {@code CompletableFuture<T>}. */
    @FunctionalInterface
    interface FutureInvoker {
        java.util.concurrent.CompletableFuture<?> invoke(Object[] arguments) throws Throwable;
    }

    /** Binding for generated wrappers of methods that return {@code CompletableFuture<T>}. */
    static AsyncMethodBinding typedFuture(
            Class<?> owner,
            String methodName,
            Class<?>[] erasedParameterTypes,
            FutureInvoker delegate
    ) {
        Objects.requireNonNull(delegate, "delegate");
        AsyncMethodBinding typed = typed(owner, methodName, erasedParameterTypes, delegate::invoke);
        return new AsyncMethodBinding() {
            @Override
            public void invoke(Object[] arguments) throws Throwable {
                delegate.invoke(arguments);
            }

            @Override
            public boolean returnsFuture() {
                return true;
            }

            @Override
            public Object invokeForResult(Object[] arguments) throws Throwable {
                return delegate.invoke(arguments);
            }

            @Override
            public Type[] parameterTypes() {
                return typed.parameterTypes();
            }
        };
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
