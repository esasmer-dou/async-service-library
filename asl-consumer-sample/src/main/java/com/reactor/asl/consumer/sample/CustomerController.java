package com.reactor.asl.consumer.sample;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService customerService;
    private final CustomerServiceImpl delegate;

    public CustomerController(CustomerService customerService, CustomerServiceImpl delegate) {
        this.customerService = customerService;
        this.delegate = delegate;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerView register(@RequestBody CreateCustomerRequest request) {
        return customerService.register(request);
    }

    @GetMapping
    public List<CustomerView> list() {
        return customerService.list();
    }

    @GetMapping("/{customerId}")
    public CustomerView get(@PathVariable("customerId") String customerId) {
        return customerService.get(customerId);
    }

    @PostMapping("/{customerId}/activate")
    public CustomerView activate(@PathVariable("customerId") String customerId) {
        return customerService.activate(customerId);
    }

    @PostMapping("/{customerId}/publish-welcome")
    public Map<String, String> publishWelcome(@PathVariable("customerId") String customerId) {
        customerService.publishWelcome(customerId);
        return Map.of("status", "accepted", "customerId", customerId);
    }

    @GetMapping("/welcome-events")
    public List<String> welcomeEvents() {
        return delegate.welcomeEvents();
    }
}
