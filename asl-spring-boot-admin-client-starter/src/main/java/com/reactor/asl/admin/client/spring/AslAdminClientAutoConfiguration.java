package com.reactor.asl.admin.client.spring;

import com.reactor.asl.admin.client.AslAdminClient;
import com.reactor.asl.admin.client.AslAdminClientSettings;
import com.reactor.asl.admin.client.JdkHttpAdminTransport;
import com.reactor.asl.admin.protocol.AslAdminSigner;
import com.reactor.asl.core.BufferAdminProvider;
import com.reactor.asl.core.GovernanceRegistry;
import com.reactor.asl.spring.boot.AslCoreAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

@AutoConfiguration(
        after = AslCoreAutoConfiguration.class,
        afterName = "com.reactor.asl.spring.boot.AslMapDbAutoConfiguration"
)
@ConditionalOnBean(GovernanceRegistry.class)
@ConditionalOnProperty(prefix = "asl.admin.client", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AslAdminClientProperties.class)
public class AslAdminClientAutoConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    AslAdminClient aslAdminClient(
            AslAdminClientProperties properties,
            GovernanceRegistry registry,
            ObjectProvider<BufferAdminProvider> providers,
            Environment environment
    ) {
        if (isBlank(properties.getServerUrl())) {
            throw new IllegalStateException("asl.admin.client.enabled=true requires asl.admin.client.server-url");
        }
        if (isBlank(properties.getSecret())) {
            throw new IllegalStateException("asl.admin.client.enabled=true requires asl.admin.client.secret");
        }
        AslAdminClientSettings settings = new AslAdminClientSettings(
                firstNonBlank(properties.getApplication(), environment.getProperty("spring.application.name"), "application"),
                firstNonBlank(properties.getInstance(), System.getenv("HOSTNAME"), hostName()),
                libraryVersion(),
                properties.getInterval().toMillis(),
                properties.getMaxBackoff().toMillis(),
                properties.isQueueBrowse(),
                properties.getBrowseLimit(),
                properties.getMaxErrorChars()
        );
        List<BufferAdminProvider> bufferProviders = providers.orderedStream().toList();
        return new AslAdminClient(
                settings,
                registry,
                bufferProviders,
                new JdkHttpAdminTransport(properties.getServerUrl(), properties.getConnectTimeout(), properties.getRequestTimeout()),
                new AslAdminSigner(properties.getSecret())
        );
    }

    @Bean
    SmartLifecycle aslAdminClientLifecycle(AslAdminClient client) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                client.start();
                running = true;
            }

            @Override
            public void stop() {
                client.close();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            /** Starts after the application is wired, stops before the queue engine shuts down. */
            @Override
            public int getPhase() {
                return Integer.MAX_VALUE - 100;
            }
        };
    }

    private static String libraryVersion() {
        String version = AslAdminClient.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "instance-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        throw new IllegalStateException("No value available");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
