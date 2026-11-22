package com.reactor.asl.spring.boot;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks per-method maps such as {@code asl.runtime.services} against the property names as written.
 *
 * <p>Service ids and method signatures contain '.', '(' and ','. Spring Boot keeps such a map key only
 * inside brackets; without them the key is split or dropped while binding, the map comes out empty or
 * wrong, and the setting silently never applies. This check finds every written property under the
 * prefix that did not end up in the bound map and fails startup with the fix.</p>
 */
public final class AslServiceKeys {
    private AslServiceKeys() {
    }

    public static String hint(String prefix, String example) {
        return " Service ids and method signatures contain '.', '(' or ',', so write them in brackets: in YAML \"[mail.service]\""
                + " and \"[send(java.lang.String)]\", in properties " + prefix + "[mail.service].methods[send(java.lang.String)]." + example + ".";
    }

    /**
     * @param bound service id to the method ids bound under it
     * @throws IllegalStateException when a property under {@code prefix} matches no bound service and method
     */
    public static void requireBound(Environment environment, String prefix, Map<String, ? extends Set<String>> bound, String example) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith(lowerPrefix + ".") && !lower.startsWith(lowerPrefix + "[")) {
                    continue;
                }
                if (!matches(name, bound)) {
                    throw new IllegalStateException("'" + name + "' (from " + source.getName() + ") does not reach any governed method,"
                            + " so it would be ignored." + hint(prefix, example));
                }
            }
        }
    }

    private static boolean matches(String name, Map<String, ? extends Set<String>> bound) {
        for (Map.Entry<String, ? extends Set<String>> service : bound.entrySet()) {
            if (!contains(name, service.getKey())) {
                continue;
            }
            for (String method : service.getValue()) {
                if (contains(name, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String name, String key) {
        return name.contains("[" + key + "]") || name.contains("." + key + ".");
    }
}
