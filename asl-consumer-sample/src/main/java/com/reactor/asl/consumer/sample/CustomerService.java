package com.reactor.asl.consumer.sample;

import com.reactor.asl.annotations.Excluded;
import com.reactor.asl.annotations.GovernedMethod;
import com.reactor.asl.annotations.GovernedService;

import java.util.List;

@GovernedService(id = "customer.service")
public interface CustomerService {
    @GovernedMethod(initialMaxConcurrency = 6, unavailableMessage = "customer registration lane closed")
    CustomerView register(CreateCustomerRequest request);

    @Excluded
    List<CustomerView> list();

    @Excluded
    CustomerView get(String customerId);

    @GovernedMethod(initialMaxConcurrency = 3, unavailableMessage = "customer activation lane closed")
    CustomerView activate(String customerId);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publishWelcome(String customerId);
}
