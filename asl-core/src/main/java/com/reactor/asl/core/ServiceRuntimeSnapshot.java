package com.reactor.asl.core;

import java.util.List;

public record ServiceRuntimeSnapshot(String serviceId, List<MethodRuntimeSnapshot> methods) {
}
