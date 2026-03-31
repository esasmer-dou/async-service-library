package com.reactor.asl.processor;

import com.reactor.asl.annotations.Excluded;
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
import javax.lang.model.element.VariableElement;
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

    private List<MethodModel> collectMethods(TypeElement typeElement) {
        List<MethodModel> methods = new ArrayList<>();
        int managedIndex = 0;
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.isDefault() || method.getModifiers().contains(Modifier.STATIC)) {
                continue;
            }

            boolean excluded = method.getAnnotation(Excluded.class) != null;
            GovernedMethod governedMethod = method.getAnnotation(GovernedMethod.class);
            String methodId = governedMethod != null && !governedMethod.id().isBlank()
                    ? governedMethod.id()
                    : methodSignature(method);
            String unavailableMessage = governedMethod == null ? "" : governedMethod.unavailableMessage();
            boolean initiallyEnabled = governedMethod == null || governedMethod.initiallyEnabled();
            int initialMaxConcurrency = governedMethod == null
                    ? Integer.MAX_VALUE
                    : governedMethod.initialMaxConcurrency();
            boolean asyncCapable = governedMethod != null && governedMethod.asyncCapable();
            int initialConsumerThreads = governedMethod == null ? 0 : governedMethod.initialConsumerThreads();

            if (asyncCapable && !"void".equals(method.getReturnType().toString())) {
                error(method, "@GovernedMethod(asyncCapable=true) is currently supported only for void methods");
                continue;
            }

            methods.add(new MethodModel(
                    method,
                    excluded,
                    methodId,
                    method.getSimpleName().toString(),
                    unavailableMessage,
                    initiallyEnabled,
                    initialMaxConcurrency,
                    asyncCapable,
                    initialConsumerThreads,
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
        String interfaceName = typeElement.getSimpleName().toString();
        String factoryName = interfaceName + "Asl";
        String wrapperName = interfaceName + "AslGoverned";
        String qualifiedName = packageName.isBlank() ? factoryName : packageName + "." + factoryName;
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
            writer.write("    public static " + interfaceName + " wrap(" + interfaceName
                    + " delegate, GovernanceRegistry registry) {\n");
            writer.write("        return new " + wrapperName + "(delegate, registry);\n");
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
        String interfaceName = typeElement.getSimpleName().toString();
        String wrapperName = interfaceName + "AslGoverned";
        String factoryName = interfaceName + "Asl";
        String qualifiedName = packageName.isBlank() ? wrapperName : packageName + "." + wrapperName;

        JavaFileObject file = filer.createSourceFile(qualifiedName, typeElement);
        try (Writer writer = file.openWriter()) {
            if (!packageName.isBlank()) {
                writer.write("package " + packageName + ";\n\n");
            }
            writer.write("import com.reactor.asl.core.GovernanceRegistry;\n");
            writer.write("import com.reactor.asl.core.ExecutionMode;\n");
            writer.write("import com.reactor.asl.core.MethodRuntime;\n");
            writer.write("import com.reactor.asl.core.ServiceRuntime;\n");
            writer.write("import com.reactor.asl.core.Throwables;\n");
            writer.write("import java.util.Objects;\n\n");
            writer.write("public final class " + wrapperName + " implements " + interfaceName + " {\n");
            writer.write("    private final " + interfaceName + " delegate;\n");
            writer.write("    private final GovernanceRegistry registry;\n");
            for (MethodModel method : methods) {
                if (!method.excluded()) {
                    writer.write("    private final MethodRuntime method" + method.managedIndex() + ";\n");
                }
            }
            writer.write("\n");
            writer.write("    public " + wrapperName + "(" + interfaceName + " delegate, GovernanceRegistry registry) {\n");
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
                    writer.write("        this.registry.registerAsyncMethod(this.method" + method.managedIndex() + ", arguments -> this.delegate."
                            + method.element().getSimpleName() + "(" + bindingArguments(method.element()) + "));\n");
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
            writer.write("public final class " + configName + " {\n");
            for (int i = 0; i < governedTypes.size(); i++) {
                TypeElement governedType = governedTypes.get(i);
                String beanMethodName = springWrapMethodName(implementationType, governedType, i);
                writer.write("    @Bean\n");
                writer.write("    @Primary\n");
                writer.write("    public " + governedType.getQualifiedName() + " " + beanMethodName + "("
                        + implementationType.getQualifiedName() + " delegate, "
                        + "com.reactor.asl.core.GovernanceRegistry registry) {\n");
                writer.write("        return " + governedType.getQualifiedName() + "Asl.wrap(delegate, registry);\n");
                writer.write("    }\n\n");
            }
            writer.write("}\n");
        }
    }

    private void writeMethod(Writer writer, MethodModel method) throws IOException {
        ExecutableElement executableElement = method.element();
        writer.write("    @Override\n");
        writer.write("    public " + executableElement.getReturnType() + " " + executableElement.getSimpleName() + "(");

        List<? extends VariableElement> parameters = executableElement.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement parameter = parameters.get(i);
            if (i > 0) {
                writer.write(", ");
            }
            writer.write(parameter.asType() + " " + parameter.getSimpleName());
        }
        writer.write(")");
        List<? extends javax.lang.model.type.TypeMirror> thrownTypes = executableElement.getThrownTypes();
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

        String invocation = "this.delegate." + executableElement.getSimpleName() + "(" + joinParameterNames(parameters) + ")";
        if (method.excluded()) {
            if (isVoid(executableElement)) {
                writer.write("        " + invocation + ";\n");
            } else {
                writer.write("        return " + invocation + ";\n");
            }
            writer.write("    }\n\n");
            return;
        }

        if (method.asyncCapable()) {
            writer.write("        if (this.method" + method.managedIndex() + ".executionMode() == ExecutionMode.ASYNC) {\n");
            writer.write("            this.registry.enqueueAsync(this.method" + method.managedIndex() + ", " + asyncArgumentsArray(parameters) + ");\n");
            writer.write("            return;\n");
            writer.write("        }\n");
        }

        writer.write("        if (!this.method" + method.managedIndex() + ".tryAcquire()) {\n");
        writer.write("            throw this.method" + method.managedIndex() + ".unavailableException();\n");
        writer.write("        }\n");
        writer.write("        try {\n");
        if (isVoid(executableElement)) {
            writer.write("            " + invocation + ";\n");
            writer.write("            this.method" + method.managedIndex() + ".onSuccess();\n");
        } else {
            writer.write("            " + executableElement.getReturnType() + " result = " + invocation + ";\n");
            writer.write("            this.method" + method.managedIndex() + ".onSuccess();\n");
            writer.write("            return result;\n");
        }
        writer.write("        } catch (Throwable throwable) {\n");
        writer.write("            this.method" + method.managedIndex() + ".onError(throwable);\n");
        if (isVoid(executableElement)) {
            writer.write("            Throwables.sneakyThrow(throwable);\n");
            writer.write("            return;\n");
        } else {
            writer.write("            return Throwables.sneakyThrow(throwable);\n");
        }
        writer.write("        } finally {\n");
        writer.write("            this.method" + method.managedIndex() + ".release();\n");
        writer.write("        }\n");
        writer.write("    }\n\n");
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

    private static String bindingArguments(ExecutableElement executableElement) {
        List<? extends VariableElement> parameters = executableElement.getParameters();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(castArgument(parameters.get(i).asType(), i));
        }
        return builder.toString();
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

    private static boolean isVoid(ExecutableElement executableElement) {
        return "void".equals(executableElement.getReturnType().toString());
    }

    private boolean hasSpringStereotype(TypeElement typeElement) {
        return typeElement.getAnnotationMirrors().stream()
                .map(annotationMirror -> annotationMirror.getAnnotationType().toString())
                .anyMatch(SPRING_STEREOTYPES::contains);
    }

    private List<TypeElement> findGovernedInterfaces(TypeElement implementationType, List<TypeElement> governedTypes) {
        List<TypeElement> matched = new ArrayList<>();
        for (TypeElement governedType : governedTypes) {
            if (processingEnv.getTypeUtils().isAssignable(implementationType.asType(), governedType.asType())) {
                matched.add(governedType);
            }
        }
        return matched;
    }

    private String packageName(TypeElement typeElement) {
        PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(typeElement);
        return packageElement.isUnnamed() ? "" : packageElement.getQualifiedName().toString();
    }

    private String methodSignature(ExecutableElement executableElement) {
        StringBuilder builder = new StringBuilder();
        builder.append(executableElement.getSimpleName()).append('(');
        List<? extends VariableElement> parameters = executableElement.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameters.get(i).asType());
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
            boolean excluded,
            String methodId,
            String methodName,
            String unavailableMessage,
            boolean initiallyEnabled,
            int initialMaxConcurrency,
            boolean asyncCapable,
            int initialConsumerThreads,
            int managedIndex
    ) {
    }

    private record SpringImplementationModel(
            TypeElement implementationType,
            List<TypeElement> governedInterfaces
    ) {
    }
}
