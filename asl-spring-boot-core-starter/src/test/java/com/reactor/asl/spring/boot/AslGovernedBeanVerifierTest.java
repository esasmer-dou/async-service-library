package com.reactor.asl.spring.boot;

import com.reactor.asl.spring.boot.verify.Probe;
import com.reactor.asl.spring.boot.verify.ProbeAslGoverned;
import com.reactor.asl.spring.boot.verify.ProbeImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AslGovernedBeanVerifierTest {
    @Test
    void detectsGovernedImplementationWithoutWrapperBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("probeImpl", new ProbeImpl());

        Map<String, List<String>> violations = AslGovernedBeanVerifier.findUnwrapped(beanFactory);

        assertEquals(Map.of(Probe.class.getName(), List.of("probeImpl")), violations);
        AslGovernedBeanVerifier failing = new AslGovernedBeanVerifier(UnwrappedGovernedBeanPolicy.FAIL);
        failing.setBeanFactory(beanFactory);
        IllegalStateException failure = assertThrows(IllegalStateException.class, failing::afterSingletonsInstantiated);
        assertTrue(failure.getMessage().contains(Probe.class.getName()));
    }

    @Test
    void acceptsImplementationWhenWrapperBeanExists() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ProbeImpl implementation = new ProbeImpl();
        beanFactory.registerSingleton("probeImpl", implementation);
        beanFactory.registerSingleton("probeGoverned", new ProbeAslGoverned(implementation));

        assertTrue(AslGovernedBeanVerifier.findUnwrapped(beanFactory).isEmpty());
        AslGovernedBeanVerifier failing = new AslGovernedBeanVerifier(UnwrappedGovernedBeanPolicy.FAIL);
        failing.setBeanFactory(beanFactory);
        assertDoesNotThrow(failing::afterSingletonsInstantiated);
    }

    @Test
    void warnAndIgnorePoliciesDoNotFailStartup() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("probeImpl", new ProbeImpl());
        for (UnwrappedGovernedBeanPolicy policy : List.of(UnwrappedGovernedBeanPolicy.WARN, UnwrappedGovernedBeanPolicy.IGNORE)) {
            AslGovernedBeanVerifier verifier = new AslGovernedBeanVerifier(policy);
            verifier.setBeanFactory(beanFactory);
            assertDoesNotThrow(verifier::afterSingletonsInstantiated);
        }
    }

    interface NestedGoverned {
    }

    @Test
    void wrapperNamingMatchesProcessorForNestedInterfaces() {
        assertEquals("com.reactor.asl.spring.boot.verify.ProbeAslGoverned", AslGovernedBeanVerifier.wrapperClassName(Probe.class));
        assertEquals(
                "com.reactor.asl.spring.boot.AslGovernedBeanVerifierTest_NestedGovernedAslGoverned",
                AslGovernedBeanVerifier.wrapperClassName(NestedGoverned.class)
        );
    }
}
