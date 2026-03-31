package com.reactor.asl.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface GovernedMethod {
    String id() default "";
    boolean initiallyEnabled() default true;
    int initialMaxConcurrency() default Integer.MAX_VALUE;
    String unavailableMessage() default "";
    boolean asyncCapable() default false;
    int initialConsumerThreads() default 1;
}
