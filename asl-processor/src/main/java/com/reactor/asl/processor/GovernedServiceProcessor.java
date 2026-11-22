package com.reactor.asl.processor;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.AsyncIdempotencyKey;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SupportedAnnotationTypes("com.reactor.asl.annotations.GovernedService")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class GovernedServiceProcessor extends AbstractProcessor {
    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository"
    );

    private Messager messager;
    private Filer filer;
    private final Map<String, TypeElement> governedInterfaces = new LinkedHashMap<>();
    private final Set<String> generatedSpringConfigurations = new LinkedHashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(GovernedService.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                continue;
            }
            if (typeElement.getKind() != ElementKind.INTERFACE) {
                error(typeElement, "@GovernedService can only be applied to interfaces");
                continue;
            }
            if (typeElement.getModifiers().contains(Modifier.PRIVATE)) {
                error(typeElement, "@GovernedService interface cannot be private");
                continue;
            }
            if (!validateNesting(typeElement)) {
                continue;
            }

            List<MethodModel> methods = collectMethods(typeElement);
            if (!validateMethodIds(typeElement, methods)) {
                continue;
            }

            try {
                generateFactory(typeElement, methods);
                generateWrapper(typeElement, methods);
                governedInterfaces.put(typeElement.getQualifiedName().toString(), typeElement);
            } catch (IOException e) {
                error(typeElement, "Failed to generate governed wrapper: %s", e.getMessage());
            }
        }
        generateSpringConfigurations(roundEnv);
        return false;
    }

    private void generateSpringConfigurations(RoundEnvironment roundEnv) {
        if (governedInterfaces.isEmpty()) {
            return;
        }

        List<TypeElement> governedTypes = new ArrayList<>(governedInterfaces.values());
        Map<String, SpringImplementationModel> implementations = new LinkedHashMap<>();
        Map<String, List<TypeElement>> implementationByInterface = new LinkedHashMap<>();

        for (Element rootElement : roundEnv.getRootElements()) {
            if (!(rootElement instanceof TypeElement typeElement)) {
                continue;
            }
            if (typeElement.getKind() != ElementKind.CLASS) {
                continue;
            }
            if (typeElement.getNestingKind() != NestingKind.TOP_LEVEL) {
                continue;
            }
            if (typeElement.getModifiers().contains(Modifier.ABSTRACT) || typeElement.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            if (!hasSpringStereotype(typeElement)) {
                continue;
            }

            List<TypeElement> matchedInterfaces = findGovernedInterfaces(typeElement, governedTypes);
            if (matchedInterfaces.isEmpty()) {
                continue;
            }

            SpringImplementationModel model = implementations.computeIfAbsent(
                    typeElement.getQualifiedName().toString(),
                    ignored -> new SpringImplementationModel(typeElement, new ArrayList<>())
            );
            model.governedInterfaces().addAll(matchedInterfaces);
            for (TypeElement matchedInterface : matchedInterfaces) {
                implementationByInterface
                        .computeIfAbsent(matchedInterface.getQualifiedName().toString(), ignored -> new ArrayList<>())
                        .add(typeElement);
            }
        }

        Set<String> ambiguousInterfaces = new LinkedHashSet<>();
        for (Map.Entry<String, List<TypeElement>> entry : implementationByInterface.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            ambiguousInterfaces.add(entry.getKey());
            TypeElement governedInterface = governedInterfaces.get(entry.getKey());
            if (governedInterface != null) {
                error(governedInterface,
                        "Multiple Spring stereotype implementations found for governed interface %s: %s",
                        entry.getKey(),
                        joinQualifiedNames(entry.getValue()));
            }
        }

        for (SpringImplementationModel implementation : implementations.values()) {
            List<TypeElement> resolvedInterfaces = implementation.governedInterfaces().stream()
                    .filter(candidate -> !ambiguousInterfaces.contains(candidate.getQualifiedName().toString()))
                    .toList();
            if (resolvedInterfaces.isEmpty()) {
                continue;
            }
            String packageName = packageName(implementation.implementationType());
            String configName = implementation.implementationType().getSimpleName() + "AslSpringConfiguration";
            String qualifiedName = packageName.isBlank() ? configName : packageName + "." + configName;
            if (!generatedSpringConfigurations.add(qualifiedName)) {
                continue;
            }
            try {
                generateSpringConfiguration(packageName, configName, implementation.implementationType(), resolvedInterfaces);
            } catch (IOException e) {
                error(implementation.implementationType(),
                        "Failed to generate Spring governed configuration: %s",
                        e.getMessage());
            }
        }
    }

    private boolean validateNesting(TypeElement typeElement) {
        Element current = typeElement;
        while (current instanceof TypeElement type) {
            if (type.getNestingKind() == NestingKind.LOCAL || type.getNestingKind() == NestingKind.ANONYMOUS) {
                error(typeElement, "@GovernedService does not support local interfaces");
                return false;
            }
            if (type != typeElement && type.getModifiers().contains(Modifier.PRIVATE)) {
                error(typeElement, "@GovernedService interface must not be nested in a private type");
                return false;
            }
            current = type.getEnclosingElement();
        }
        return true;
    }

    /**
     * Abstract methods of the governed interface including inherited ones, own methods first, then
     * super-interfaces depth-first in declaration order. Signatures already seen (including ones
     * overridden by a default method) are skipped. Types are resolved as seen from the governed
     * interface, so {@code interface Orders extends Crud<Order>} yields {@code save(Order)}.
     */
    private List<ExecutableElement> abstractMethods(TypeElement governed) {
        List<ExecutableElement> result = new ArrayList<>();
        collectAbstractMethods(governed, (DeclaredType) governed.asType(), new LinkedHashSet<>(), new LinkedHashSet<>(), result);
        return result;
    }

    private void collectAbstractMethods(
            TypeElement type,
            DeclaredType governedType,
            Set<String> seenSignatures,
            Set<String> visitedTypes,
            List<ExecutableElement> result
    ) {
        if (!visitedTypes.add(type.getQualifiedName().toString())
                || "java.lang.Object".contentEquals(type.getQualifiedName())) {
            return;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getModifiers().contains(Modifier.STATIC) || method.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            if (!seenSignatures.add(erasedSignature(method, governedType))) {
                continue;
            }
            if (!method.isDefault()) {
                result.add(method);
            }
        }
        for (TypeMirror superInterface : type.getInterfaces()) {
            Element element = processingEnv.getTypeUtils().asElement(superInterface);
            if (element instanceof TypeElement superType) {
                collectAbstractMethods(superType, governedType, seenSignatures, visitedTypes, result);
            }
        }
    }

    private String erasedSignature(ExecutableElement method, DeclaredType governedType) {
        ExecutableType resolved = resolve(method, governedType);
        StringBuilder builder = new StringBuilder(method.getSimpleName()).append('(');
        for (TypeMirror parameter : resolved.getParameterTypes()) {
            builder.append(processingEnv.getTypeUtils().erasure(parameter)).append(',');
        }
        return builder.append(')').toString();
    }

    private ExecutableType resolve(ExecutableElement method, DeclaredType governedType) {
        return (ExecutableType) processingEnv.getTypeUtils().asMemberOf(governedType, method);
    }

    private List<MethodModel> collectMethods(TypeElement typeElement) {
        List<MethodModel> methods = new ArrayList<>();
        int managedIndex = 0;
        DeclaredType governedType = (DeclaredType) typeElement.asType();
        for (ExecutableElement method : abstractMethods(typeElement)) {
            ExecutableType resolved = resolve(method, governedType);

            boolean excluded = method.getAnnotation(Excluded.class) != null;
            GovernedMethod governedMethod = method.getAnnotation(GovernedMethod.class);
            String methodId = governedMethod != null && !governedMethod.id().isBlank()
                    ? governedMethod.id()
                    : methodSignature(method, resolved);
            String unavailableMessage = governedMethod == null ? "" : governedMethod.unavailableMessage();
            boolean initiallyEnabled = governedMethod == null || governedMethod.initiallyEnabled();
            int initialMaxConcurrency = governedMethod == null
                    ? Integer.MAX_VALUE
                    : governedMethod.initialMaxConcurrency();
            boolean asyncCapable = governedMethod != null && governedMethod.asyncCapable();
            int initialConsumerThreads = governedMethod == null || !asyncCapable
                    ? 0
                    : governedMethod.initialConsumerThreads();

            if (asyncCapable && resolved.getReturnType().getKind() != TypeKind.VOID) {
                error(method, "@GovernedMethod(asyncCapable=true) is currently supported only for void methods");
                continue;
            }

            int idempotencyParameterIndex = -1;
            boolean invalidIdempotencyParameter = false;
            List<? extends VariableElement> parameters = method.getParameters();
            for (int parameterIndex = 0; parameterIndex < parameters.size(); parameterIndex++) {
                VariableElement parameter = parameters.get(parameterIndex);
                if (parameter.getAnnotation(AsyncIdempotencyKey.class) == null) {
                    continue;
                }
                if (!asyncCapable) {
                    error(parameter, "@AsyncIdempotencyKey is supported only on async-capable methods");
                    invalidIdempotencyParameter = true;
                }
                if (!"java.lang.String".equals(resolved.getParameterTypes().get(parameterIndex).toString())) {
                    error(parameter, "@AsyncIdempotencyKey parameter type must be java.lang.String");
                    invalidIdempotencyParameter = true;
                }
                if (idempotencyParameterIndex >= 0) {
                    error(parameter, "Only one @AsyncIdempotencyKey parameter is allowed per method");
                    invalidIdempotencyParameter = true;
                } else {
                    idempotencyParameterIndex = parameterIndex;
                }
            }
            if (invalidIdempotencyParameter) {
                continue;
            }

            methods.add(new MethodModel(
                    method,
                    resolved,
                    excluded,
                    methodId,
                    method.getSimpleName().toString(),
                    unavailableMessage,
                    initiallyEnabled,
                    initialMaxConcurrency,
                    asyncCapable,
                    initialConsumerThreads,
                    idempotencyParameterIndex,
                    excluded ? -1 : managedIndex++
            ));
        }
        return methods;
    }

    private boolean validateMethodIds(TypeElement typeElement, List<MethodModel> methods) {
        Set<String> ids = new LinkedHashSet<>();
        for (MethodModel method : methods) {
            if (method.excluded()) {
                continue;
            }
            if (!ids.add(method.methodId())) {
                error(typeElement, "Duplicate governed method id detected: %s", method.methodId());
                return false;
            }
        }
        return true;
    }

    private void generateFactory(TypeElement typeElement, List<MethodModel> methods) throws IOException {
        String packageName = packageName(typeElement);
        String baseName = generatedBaseName(typeElement);
        String factoryName = baseName + "Asl";
        String wrapperName = baseName + "AslGoverned";
        String qualifiedName = packageName.isBlank() ? factoryName : packageName + "." + factoryName;
        String typeParameters = typeParameterDeclarations(typeElement.getTypeParameters());
        String typeArguments = typeArguments(typeElement.getTypeParameters());
        String interfaceType = typeElement.getQualifiedName() + typeArguments;
        GovernedService governedService = typeElement.getAnnotation(GovernedService.class);
        String serviceId = governedService != null && !governedService.id().isBlank()
                ? governedService.id()
                : typeElement.getQualifiedName().toString();

        JavaFileObject file = filer.createSourceFile(qualifiedName, typeElement);
        try (Writer writer = file.openWriter()) {
            if (!packageName.isBlank()) {
                writer.write("package " + packageName + ";\n\n");
            }
            writer.write("import com.reactor.asl.core.GovernanceRegistry;\n");
            writer.write("import com.reactor.asl.core.MethodDescriptor;\n");
            writer.write("import com.reactor.asl.core.ServiceDescriptor;\n\n");
            writer.write("public final class " + factoryName + " {\n");
            writer.write("    public static final String SERVICE_ID = " + stringLiteral(serviceId) + ";\n");
            int constantIndex = 0;
            for (MethodModel method : methods) {
                if (method.excluded()) {
                    continue;
                }
                writer.write("    public static final String METHOD_" + constantIndex++ + "_ID = "
                        + stringLiteral(method.methodId()) + ";\n");
            }
            writer.write("\n");
            writer.write("    private " + factoryName + "() {\n");
            writer.write("    }\n\n");
            writer.write("    public static " + (typeParameters.isEmpty() ? "" : typeParameters + " ") + interfaceType
                    + " wrap(" + interfaceType + " delegate, GovernanceRegistry registry) {\n");
            writer.write("        return new " + wrapperName + (typeArguments.isEmpty() ? "" : "<>")
                    + "(delegate, registry);\n");
            writer.write("    }\n\n");
            writer.write("    static ServiceDescriptor descriptor() {\n");
            writer.write("        return new ServiceDescriptor(SERVICE_ID, new MethodDescriptor[] {\n");
            boolean first = true;
            for (MethodModel method : methods) {
                if (method.excluded()) {
                    continue;
                }
                if (!first) {
                    writer.write(",\n");
                }
                writer.write("                new MethodDescriptor("
                        + stringLiteral(method.methodId()) + ", "
                        + stringLiteral(method.methodName()) + ", "
                        + method.initiallyEnabled() + ", "
                        + method.initialMaxConcurrency() + ", "
                        + stringLiteral(method.unavailableMessage()) + ", "
                        + method.asyncCapable() + ", "
                        + method.initialConsumerThreads() + ")");
                first = false;
            }
            writer.write("\n        });\n");
            writer.write("    }\n");
            writer.write("}\n");
        }
    }

    private void generateWrapper(TypeElement typeElement, List<MethodModel> methods) throws IOException {
        String packageName = packageName(typeElement);
        String baseName = generatedBaseName(typeElement);
        String wrapperName = baseName + "AslGoverned";
        String factoryName = baseName + "Asl";
        String qualifiedName = packageName.isBlank() ? wrapperName : packageName + "." + wrapperName;
        String typeParameters = typeParameterDeclarations(typeElement.getTypeParameters());
        String interfaceType = typeElement.getQualifiedName() + typeArguments(typeElement.getTypeParameters());
        String interfaceClassLiteral = typeElement.getQualifiedName() + ".class";

        JavaFileObject file = filer.createSourceFile(qualifiedName, typeElement);
        try (Writer writer = file.openWriter()) {
            if (!packageName.isBlank()) {
                writer.write("package " + packageName + ";\n\n");
            }
            writer.write("import com.reactor.asl.core.GovernanceRegistry;\n");
            writer.write("import com.reactor.asl.core.AsyncInvocationContext;\n");
            writer.write("import com.reactor.asl.core.ExecutionMode;\n");
            writer.write("import com.reactor.asl.core.MethodRuntime;\n");
            writer.write("import com.reactor.asl.core.ServiceRuntime;\n");
            writer.write("import com.reactor.asl.core.Throwables;\n");
            writer.write("import java.util.Objects;\n\n");
            writer.write("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            writer.write("public final class " + wrapperName + typeParameters + " implements " + interfaceType + " {\n");
            writer.write("    private final " + interfaceType + " delegate;\n");
            writer.write("    private final GovernanceRegistry registry;\n");
            for (MethodModel method : methods) {
                if (!method.excluded()) {
                    writer.write("    private final MethodRuntime method" + method.managedIndex() + ";\n");
                }
            }
            writer.write("\n");
            writer.write("    public " + wrapperName + "(" + interfaceType + " delegate, GovernanceRegistry registry) {\n");
            writer.write("        this.delegate = Objects.requireNonNull(delegate, \"delegate\");\n");
            writer.write("        this.registry = Objects.requireNonNull(registry, \"registry\");\n");
            writer.write("        ServiceRuntime runtime = this.registry.register(" + factoryName + ".descriptor());\n");
            for (MethodModel method : methods) {
                if (!method.excluded()) {
                    writer.write("        this.method" + method.managedIndex() + " = runtime.method(" + method.managedIndex() + ");\n");
                }
            }
            for (MethodModel method : methods) {
                if (method.asyncCapable()) {
                    writer.write("        this.registry.registerAsyncMethod(this.method" + method.managedIndex()
                            + ", com.reactor.asl.core.AsyncMethodBinding.typed(" + interfaceClassLiteral + ", "
                            + stringLiteral(method.element().getSimpleName().toString()) + ", "
                            + erasedParameterClasses(method)
                            + ", arguments -> this.delegate."
                            + method.element().getSimpleName() + "(" + bindingArguments(method) + ")));\n");
                }
            }
            writer.write("    }\n\n");

            for (MethodModel method : methods) {
                writeMethod(writer, method);
            }
            writer.write("}\n");
        }
    }

    private void generateSpringConfiguration(
            String packageName,
            String configName,
            TypeElement implementationType,
            List<TypeElement> governedTypes
    ) throws IOException {
        String qualifiedName = packageName.isBlank() ? configName : packageName + "." + configName;
        JavaFileObject file = filer.createSourceFile(qualifiedName, implementationType);
        try (Writer writer = file.openWriter()) {
            if (!packageName.isBlank()) {
                writer.write("package " + packageName + ";\n\n");
            }
            writer.write("import org.springframework.context.annotation.Bean;\n");
            writer.write("import org.springframework.context.annotation.Configuration;\n");
            writer.write("import org.springframework.context.annotation.Primary;\n\n");
            writer.write("@Configuration(proxyBeanMethods = false)\n");
            writer.write("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
            writer.write("public final class " + configName + " {\n");
            for (int i = 0; i < governedTypes.size(); i++) {
                TypeElement governedType = governedTypes.get(i);
                String beanMethodName = springWrapMethodName(implementationType, governedType, i);
                writer.write("    @Bean\n");
                writer.write("    @Primary\n");
                writer.write("    public " + governedType.getQualifiedName() + " " + beanMethodName + "("
                        + implementationType.getQualifiedName() + " delegate, "
                        + "com.reactor.asl.core.GovernanceRegistry registry) {\n");
                writer.write("        return " + factoryQualifiedName(governedType) + ".wrap(delegate, registry);\n");
                writer.write("    }\n\n");
            }
            writer.write("}\n");
        }
    }

    private void writeMethod(Writer writer, MethodModel method) throws IOException {
        ExecutableElement executableElement = method.element();
        ExecutableType type = method.type();
        String methodTypeParameters = typeParameterDeclarations(executableElement.getTypeParameters());
        writer.write("    @Override\n");
        writer.write("    public " + (methodTypeParameters.isEmpty() ? "" : methodTypeParameters + " ")
                + type.getReturnType() + " " + executableElement.getSimpleName() + "(");

        List<? extends VariableElement> parameters = executableElement.getParameters();
        List<? extends TypeMirror> parameterTypes = type.getParameterTypes();
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            if (i > 0) {
                writer.write(", ");
            }
            String parameterType = parameterTypes.get(i).toString();
            if (executableElement.isVarArgs() && i == parameters.size() - 1 && parameterType.endsWith("[]")) {
                parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
            }
            writer.write(parameterType + " " + parameter.getSimpleName());
        }
        writer.write(")");
        List<? extends TypeMirror> thrownTypes = type.getThrownTypes();
        if (!thrownTypes.isEmpty()) {
            writer.write(" throws ");
            for (int i = 0; i < thrownTypes.size(); i++) {
                if (i > 0) {
                    writer.write(", ");
                }
                writer.write(thrownTypes.get(i).toString());
            }
        }
        writer.write(" {\n");

        boolean isVoid = type.getReturnType().getKind() == TypeKind.VOID;
        String invocation = "this.delegate." + executableElement.getSimpleName() + "(" + joinParameterNames(parameters) + ")";
        if (method.excluded()) {
            if (isVoid) {
                writer.write("        " + invocation + ";\n");
            } else {
                writer.write("        return " + invocation + ";\n");
            }
            writer.write("    }\n\n");
            return;
        }

        if (method.asyncCapable()) {
            writer.write("        if (this.method" + method.managedIndex() + ".executionMode() == ExecutionMode.ASYNC) {\n");
            String contextArgument = method.idempotencyParameterIndex() < 0
                    ? ""
                    : ", AsyncInvocationContext.ofNullable("
                            + parameters.get(method.idempotencyParameterIndex()).getSimpleName() + ")";
            writer.write("            this.registry.enqueueAsync(this.method" + method.managedIndex() + ", "
                    + asyncArgumentsArray(parameters) + contextArgument + ");\n");
            writer.write("            return;\n");
            writer.write("        }\n");
        }

        writer.write("        if (!this.method" + method.managedIndex() + ".tryAcquire()) {\n");
        writer.write("            throw this.method" + method.managedIndex() + ".unavailableException();\n");
        writer.write("        }\n");
        writer.write("        final long aslStartedNanos = this.method" + method.managedIndex() + ".startTiming();\n");
        writer.write("        try {\n");
        if (isVoid) {
            writer.write("            " + invocation + ";\n");
            writer.write("            this.method" + method.managedIndex() + ".onSuccess();\n");
        } else {
            writer.write("            " + type.getReturnType() + " result = " + invocation + ";\n");
            writer.write("            this.method" + method.managedIndex() + ".onSuccess();\n");
            writer.write("            return result;\n");
        }
        writer.write("        } catch (Throwable throwable) {\n");
        writer.write("            this.method" + method.managedIndex() + ".onError(throwable);\n");
        if (isVoid) {
            writer.write("            Throwables.sneakyThrow(throwable);\n");
            writer.write("            return;\n");
        } else {
            writer.write("            return Throwables.sneakyThrow(throwable);\n");
        }
        writer.write("        } finally {\n");
        writer.write("            this.method" + method.managedIndex() + ".release(aslStartedNanos);\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
    }

    /** Flattened, collision-free base name: {@code Outer.Inner} becomes {@code Outer_Inner}. */
    static String generatedBaseName(TypeElement typeElement) {
        StringBuilder builder = new StringBuilder(typeElement.getSimpleName());
        Element enclosing = typeElement.getEnclosingElement();
        while (enclosing instanceof TypeElement outer) {
            builder.insert(0, outer.getSimpleName() + "_");
            enclosing = outer.getEnclosingElement();
        }
        return builder.toString();
    }

    private String factoryQualifiedName(TypeElement governedType) {
        String packageName = packageName(governedType);
        String factoryName = generatedBaseName(governedType) + "Asl";
        return packageName.isBlank() ? factoryName : packageName + "." + factoryName;
    }

    private static String typeParameterDeclarations(List<? extends TypeParameterElement> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("<");
        for (int i = 0; i < typeParameters.size(); i++) {
            TypeParameterElement parameter = typeParameters.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameter.getSimpleName());
            List<String> bounds = new ArrayList<>();
            for (TypeMirror bound : parameter.getBounds()) {
                String text = bound.toString();
                if (!"java.lang.Object".equals(text)) {
                    bounds.add(text);
                }
            }
            if (!bounds.isEmpty()) {
                builder.append(" extends ").append(String.join(" & ", bounds));
            }
        }
        return builder.append('>').toString();
    }

    private static String typeArguments(List<? extends TypeParameterElement> typeParameters) {
        if (typeParameters.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("<");
        for (int i = 0; i < typeParameters.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(typeParameters.get(i).getSimpleName());
        }
        return builder.append('>').toString();
    }

    private static String joinParameterNames(List<? extends VariableElement> parameters) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameters.get(i).getSimpleName());
        }
        return builder.toString();
    }

    private static String asyncArgumentsArray(List<? extends VariableElement> parameters) {
        if (parameters.isEmpty()) {
            return "new Object[0]";
        }
        StringBuilder builder = new StringBuilder("new Object[] { ");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameters.get(i).getSimpleName());
        }
        builder.append(" }");
        return builder.toString();
    }

    private String bindingArguments(MethodModel method) {
        List<? extends TypeMirror> parameterTypes = method.type().getParameterTypes();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            // Erased casts: method and interface type variables are not in scope in the binding lambda.
            TypeMirror parameterType = parameterTypes.get(i);
            builder.append(castArgument(
                    parameterType.getKind().isPrimitive() ? parameterType : processingEnv.getTypeUtils().erasure(parameterType),
                    i
            ));
        }
        return builder.toString();
    }

    private String erasedParameterClasses(MethodModel method) {
        List<? extends TypeMirror> parameterTypes = method.type().getParameterTypes();
        if (parameterTypes.isEmpty()) {
            return "new java.lang.Class<?>[0]";
        }
        StringBuilder builder = new StringBuilder("new java.lang.Class<?>[] { ");
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(processingEnv.getTypeUtils().erasure(parameterTypes.get(i))).append(".class");
        }
        return builder.append(" }").toString();
    }

    private static String castArgument(TypeMirror typeMirror, int index) {
        TypeKind kind = typeMirror.getKind();
        return switch (kind) {
            case BOOLEAN -> "((java.lang.Boolean) arguments[" + index + "]).booleanValue()";
            case BYTE -> "((java.lang.Byte) arguments[" + index + "]).byteValue()";
            case SHORT -> "((java.lang.Short) arguments[" + index + "]).shortValue()";
            case INT -> "((java.lang.Integer) arguments[" + index + "]).intValue()";
            case LONG -> "((java.lang.Long) arguments[" + index + "]).longValue()";
            case CHAR -> "((java.lang.Character) arguments[" + index + "]).charValue()";
            case FLOAT -> "((java.lang.Float) arguments[" + index + "]).floatValue()";
            case DOUBLE -> "((java.lang.Double) arguments[" + index + "]).doubleValue()";
            default -> "((" + typeMirror + ") arguments[" + index + "])";
        };
    }

    private boolean hasSpringStereotype(TypeElement typeElement) {
        return typeElement.getAnnotationMirrors().stream()
                .map(annotationMirror -> annotationMirror.getAnnotationType().toString())
                .anyMatch(SPRING_STEREOTYPES::contains);
    }

    private List<TypeElement> findGovernedInterfaces(TypeElement implementationType, List<TypeElement> governedTypes) {
        List<TypeElement> matched = new ArrayList<>();
        for (TypeElement governedType : governedTypes) {
            if (processingEnv.getTypeUtils().isAssignable(
                    processingEnv.getTypeUtils().erasure(implementationType.asType()),
                    processingEnv.getTypeUtils().erasure(governedType.asType()))) {
                matched.add(governedType);
            }
        }
        return matched;
    }

    private String packageName(TypeElement typeElement) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(typeElement);
        return packageElement.isUnnamed() ? "" : packageElement.getQualifiedName().toString();
    }

    private String methodSignature(ExecutableElement executableElement, ExecutableType resolved) {
        StringBuilder builder = new StringBuilder();
        builder.append(executableElement.getSimpleName()).append('(');
        List<? extends TypeMirror> parameterTypes = resolved.getParameterTypes();
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes.get(i));
        }
        builder.append(')');
        return builder.toString();
    }

    private static String stringLiteral(String value) {
        Objects.requireNonNull(value, "value");
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private void error(Element element, String message, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(message, args), element);
    }

    private static String joinQualifiedNames(List<TypeElement> types) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(types.get(i).getQualifiedName());
        }
        return builder.toString();
    }

    private static String springWrapMethodName(TypeElement implementationType, TypeElement governedType, int index) {
        return decapitalize(implementationType.getSimpleName().toString())
                + "Wrap"
                + governedType.getSimpleName().toString()
                + index;
    }

    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return value.toLowerCase();
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private record MethodModel(
            ExecutableElement element,
            ExecutableType type,
            boolean excluded,
            String methodId,
            String methodName,
            String unavailableMessage,
            boolean initiallyEnabled,
            int initialMaxConcurrency,
            boolean asyncCapable,
            int initialConsumerThreads,
            int idempotencyParameterIndex,
            int managedIndex
    ) {
    }

    private record SpringImplementationModel(
            TypeElement implementationType,
            List<TypeElement> governedInterfaces
    ) {
    }
}
