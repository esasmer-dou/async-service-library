package com.reactor.asl.consumer.sample;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CustomerServiceImpl implements CustomerService {
    private final ConcurrentMap<String, CustomerRecord> customers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> welcomeEvents = new CopyOnWriteArrayList<>();
    private final AsyncScenarioControl welcomeScenario = new AsyncScenarioControl("customer.publishWelcome");

    @Override
    public CustomerView register(CreateCustomerRequest request) {
        validate(request);
        String id = UUID.randomUUID().toString();
        CustomerRecord record = new CustomerRecord(id, request.email(), request.fullName(), Instant.now());
        customers.put(id, record);
        return toView(record);
    }

    @Override
    public List<CustomerView> list() {
        return customers.values().stream()
                .sorted(Comparator.comparing(CustomerRecord::createdAt))
                .map(this::toView)
                .toList();
    }

    @Override
    public CustomerView get(String customerId) {
        return toView(requireCustomer(customerId));
    }

    @Override
    public CustomerView activate(String customerId) {
        CustomerRecord record = requireCustomer(customerId);
        record.activate(Instant.now());
        return toView(record);
    }

    @Override
    public void publishWelcome(String customerId) {
        CustomerRecord record = requireCustomer(customerId);
        welcomeScenario.beforeInvocation("Configured async failure for customer welcome: " + customerId);
        welcomeEvents.add("WELCOME:" + record.id() + ":" + record.email());
    }

    public List<String> welcomeEvents() {
        return List.copyOf(welcomeEvents);
    }

    public void reset() {
        customers.clear();
        welcomeEvents.clear();
        welcomeScenario.reset();
    }

    public AsyncScenarioSnapshot configureWelcomeScenario(AsyncScenarioRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        welcomeScenario.configure(request.failuresRemaining(), request.processingDelayMillis());
        return welcomeScenario.snapshot();
    }

    public AsyncScenarioSnapshot welcomeScenario() {
        return welcomeScenario.snapshot();
    }

    private CustomerRecord requireCustomer(String customerId) {
        CustomerRecord record = customers.get(customerId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found: " + customerId);
        }
        return record;
    }

    private CustomerView toView(CustomerRecord record) {
        return new CustomerView(
                record.id(),
                record.email(),
                record.fullName(),
                record.active(),
                record.createdAt(),
                record.activatedAt()
        );
    }

    private static void validate(CreateCustomerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (request.fullName() == null || request.fullName().isBlank()) {
            throw new IllegalArgumentException("fullName must not be blank");
        }
    }
}
