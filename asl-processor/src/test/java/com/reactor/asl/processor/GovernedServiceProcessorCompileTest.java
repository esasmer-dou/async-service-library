package com.reactor.asl.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedServiceProcessorCompileTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesAndCompilesWrapperContract() throws Exception {
        Compilation result = compile("ContractService.java", """
                package contract;

                import com.reactor.asl.annotations.AsyncIdempotencyKey;
                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;

                @GovernedService(id = "contract.service")
                public interface ContractService {
                    String echo(String value);

                    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 1)
                    void publish(@AsyncIdempotencyKey String value);
                }
                """);

        assertTrue(result.success(), () -> result.diagnosticsText());
        assertTrue(Files.isRegularFile(result.generatedSources().resolve("contract/ContractServiceAsl.java")));
        assertTrue(Files.isRegularFile(result.generatedSources().resolve("contract/ContractServiceAslGoverned.java")));
        assertTrue(Files.isRegularFile(result.classes().resolve("contract/ContractServiceAsl.class")));
        assertTrue(Files.isRegularFile(result.classes().resolve("contract/ContractServiceAslGoverned.class")));
        String generatedWrapper = Files.readString(
                result.generatedSources().resolve("contract/ContractServiceAslGoverned.java")
        );
        assertTrue(generatedWrapper.contains("AsyncInvocationContext.ofNullable(value)"));
        String generatedFactory = Files.readString(
                result.generatedSources().resolve("contract/ContractServiceAsl.java")
        );
        assertTrue(generatedFactory.contains("\"echo\", true, 2147483647, \"\", false, 0"));
    }

    @Test
    void asyncBindingCarriesDeclaredGenericParameterTypes() throws Exception {
        Compilation result = compile("GenericPayloadService.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;
                import java.util.List;
                import java.util.Map;

                @GovernedService(id = "generic.payload.service")
                public interface GenericPayloadService {
                    @GovernedMethod(asyncCapable = true)
                    void publish(List<String> items, Map<String, Integer> counts, int priority, String[] tags);
                }
                """);

        assertTrue(result.success(), () -> result.diagnosticsText());
        String generatedWrapper = Files.readString(
                result.generatedSources().resolve("contract/GenericPayloadServiceAslGoverned.java")
        );
        assertTrue(generatedWrapper.contains(
                "AsyncMethodBinding.typed(contract.GenericPayloadService.class, \"publish\", new java.lang.Class<?>[] { "
                        + "java.util.List.class, java.util.Map.class, int.class, java.lang.String[].class }"
        ), generatedWrapper);
        assertTrue(generatedWrapper.contains(".startTiming()"));
        assertTrue(generatedWrapper.contains(".release(aslStartedNanos)"));
    }

    @Test
    void governsInheritedGenericAndNestedContractsAtRuntime() throws Exception {
        Compilation result = compile("OrderRepository.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;
                import java.util.List;

                interface Crud<T, ID> {
                    T find(ID id);

                    @GovernedMethod(initialMaxConcurrency = 1)
                    void save(T entity);

                    default int defaultsAreNotGoverned() {
                        return 1;
                    }
                }

                interface Auditable {
                    String audit(String... events);
                }

                @GovernedService(id = "order.repository")
                public interface OrderRepository extends Crud<String, Long>, Auditable {
                    <R extends Comparable<R>> R convert(Object value, Class<R> type) throws java.io.IOException;

                    @GovernedMethod(asyncCapable = true)
                    <E> void publishAll(List<E> events);
                }
                """);
        assertTrue(result.success(), () -> result.diagnosticsText());

        Compilation generic = compile("Repo.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedService;
                import java.util.Map;

                @GovernedService(id = "generic.repo")
                public interface Repo<K extends Comparable<K>, V> {
                    V get(K key);

                    Map<K, V> all();
                }
                """);
        assertTrue(generic.success(), () -> generic.diagnosticsText());
        assertTrue(Files.isRegularFile(generic.generatedSources().resolve("contract/RepoAslGoverned.java")));

        Compilation nested = compile("Outer.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedService;

                public class Outer {
                    @GovernedService(id = "nested.service")
                    public interface Inner {
                        String hello(String name);
                    }
                }
                """);
        assertTrue(nested.success(), () -> nested.diagnosticsText());
        assertTrue(Files.isRegularFile(nested.generatedSources().resolve("contract/Outer_InnerAslGoverned.java")));
        assertTrue(Files.isRegularFile(nested.generatedSources().resolve("contract/Outer_InnerAsl.java")));

        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> repositoryType = loader.loadClass("contract.OrderRepository");
            Object delegate = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{repositoryType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "find" -> "order-" + args[0];
                        case "audit" -> String.join(",", (String[]) args[0]);
                        default -> null;
                    });
            com.reactor.asl.core.GovernanceRegistry registry = new com.reactor.asl.core.GovernanceRegistry();
            Object governed = loader.loadClass("contract.OrderRepositoryAsl")
                    .getMethod("wrap", repositoryType, com.reactor.asl.core.GovernanceRegistry.class)
                    .invoke(null, delegate, registry);

            java.lang.reflect.Method find = repositoryType.getMethod("find", Object.class);
            java.lang.reflect.Method audit = repositoryType.getMethod("audit", String[].class);
            find.setAccessible(true); // declared in a package-private super-interface
            audit.setAccessible(true);
            assertTrue("order-7".equals(find.invoke(governed, 7L)));
            assertTrue("a,b".equals(audit.invoke(governed, (Object) new String[]{"a", "b"})));

            com.reactor.asl.core.ServiceRuntime service = registry.service("order.repository");
            assertTrue(service.methodById("find(java.lang.Long)").successCount() == 1L);
            assertTrue(service.methodById("audit(java.lang.String[])").successCount() == 1L);
            assertTrue(service.methodById("save(java.lang.String)").policy().maxConcurrency() == 1,
                    "annotation on an inherited method compiled from source is honoured");
            assertTrue(service.methodById("publishAll(java.util.List<E>)").asyncCapable());
            assertFalse(service.methods().stream().anyMatch(m -> m.methodName().equals("defaultsAreNotGoverned")));
        }
    }

    @Test
    void rejectsAsyncMethodsThatReturnAResponse() throws Exception {
        Compilation result = compile("InvalidService.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;

                @GovernedService(id = "invalid.service")
                public interface InvalidService {
                    @GovernedMethod(asyncCapable = true)
                    String invalid(String value);
                }
                """);

        assertFalse(result.success());
        assertTrue(result.diagnosticsText().contains("supported only for void methods"));
    }

    @Test
    void completableFutureMethodsCanBeAsyncAndCountTheirOutcomeWhenTheFutureCompletes() throws Exception {
        Compilation result = compile("QuoteService.java", """
                package contract;

                import com.reactor.asl.annotations.AsyncIdempotencyKey;
                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;
                import java.util.concurrent.CompletableFuture;

                @GovernedService(id = "quote.service")
                public interface QuoteService {
                    @GovernedMethod(asyncCapable = true)
                    CompletableFuture<String> quote(String symbol);

                    @GovernedMethod(asyncCapable = true)
                    <T> CompletableFuture<T> keyed(@AsyncIdempotencyKey String key, T value);
                }
                """);
        assertTrue(result.success(), () -> result.diagnosticsText());
        String wrapper = Files.readString(result.generatedSources().resolve("contract/QuoteServiceAslGoverned.java"));
        assertTrue(wrapper.contains("AsyncMethodBinding.typedFuture(contract.QuoteService.class, \"quote\""), wrapper);
        assertTrue(wrapper.contains("this.registry.enqueueAsyncForResult(this.method0, new Object[] { symbol })"), wrapper);
        assertTrue(wrapper.contains("AsyncInvocationContext.ofNullable(key)"), wrapper);

        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{result.classes().toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> serviceType = loader.loadClass("contract.QuoteService");
            java.util.concurrent.CompletableFuture<String> pending = new java.util.concurrent.CompletableFuture<>();
            Object delegate = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{serviceType},
                    (proxy, method, args) -> "quote".equals(method.getName()) ? pending : null);
            com.reactor.asl.core.GovernanceRegistry registry = new com.reactor.asl.core.GovernanceRegistry();
            Object governed = loader.loadClass("contract.QuoteServiceAsl")
                    .getMethod("wrap", serviceType, com.reactor.asl.core.GovernanceRegistry.class)
                    .invoke(null, delegate, registry);
            com.reactor.asl.core.MethodRuntime quote = registry.service("quote.service").methodById("quote(java.lang.String)");
            assertTrue(quote.asyncCapable());

            Object returned = serviceType.getMethod("quote", String.class).invoke(governed, "ACME");
            assertTrue(returned == pending, "SYNC hands back the method's own future");
            assertTrue(quote.successCount() == 0L, "nothing is counted before the future completes");
            assertTrue(quote.stats().inFlight() == 0, "the SYNC slot is released when the future is returned");
            pending.completeExceptionally(new IllegalStateException("no price"));
            assertTrue(quote.errorCount() == 1L, "a future that fails later counts as an error");

            // ASYNC without an engine is rejected up front rather than handing out a future nobody completes.
            quote.switchMode(com.reactor.asl.core.ExecutionMode.ASYNC);
            java.lang.reflect.InvocationTargetException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> serviceType.getMethod("quote", String.class).invoke(governed, "ACME"));
            assertTrue(rejected.getCause() instanceof com.reactor.asl.core.AsyncExecutionUnavailableException, rejected.getCause().toString());
        }

        Compilation stage = compile("StageService.java", """
                package contract;

                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;
                import java.util.concurrent.CompletionStage;

                @GovernedService(id = "stage.service")
                public interface StageService {
                    @GovernedMethod(asyncCapable = true)
                    CompletionStage<String> quote(String symbol);
                }
                """);
        assertFalse(stage.success());
        assertTrue(stage.diagnosticsText().contains("CompletableFuture<T>"), stage.diagnosticsText());
    }

    @Test
    void rejectsInvalidIdempotencyKeyParameterType() throws Exception {
        Compilation result = compile("InvalidKeyService.java", """
                package contract;

                import com.reactor.asl.annotations.AsyncIdempotencyKey;
                import com.reactor.asl.annotations.GovernedMethod;
                import com.reactor.asl.annotations.GovernedService;

                @GovernedService(id = "invalid-key.service")
                public interface InvalidKeyService {
                    @GovernedMethod(asyncCapable = true)
                    void publish(@AsyncIdempotencyKey long key);
                }
                """);

        assertFalse(result.success());
        assertTrue(result.diagnosticsText().contains("parameter type must be java.lang.String"));
    }

    private Compilation compile(String fileName, String source) throws Exception {
        Path sourceRoot = Files.createDirectories(tempDir.resolve("source-" + System.nanoTime()));
        Path classes = Files.createDirectories(sourceRoot.resolve("classes"));
        Path generated = Files.createDirectories(sourceRoot.resolve("generated"));
        Path sourceFile = sourceRoot.resolve(fileName);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8
        )) {
            Iterable<? extends JavaFileObject> sources = fileManager.getJavaFileObjects(sourceFile.toFile());
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of(
                            "--release", "21",
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(),
                            "-s", generated.toString()
                    ),
                    null,
                    sources
            );
            task.setProcessors(List.of(new GovernedServiceProcessor()));
            boolean success = task.call();
            String diagnosticsText = diagnostics.getDiagnostics().stream()
                    .map(diagnostic -> diagnostic.getKind() + ": " + diagnostic.getMessage(Locale.ROOT))
                    .reduce("", (left, right) -> left + right + System.lineSeparator());
            return new Compilation(success, classes, generated, diagnosticsText);
        }
    }

    private record Compilation(
            boolean success,
            Path classes,
            Path generatedSources,
            String diagnosticsText
    ) {
    }
}
