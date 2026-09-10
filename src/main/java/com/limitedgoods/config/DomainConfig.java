package com.limitedgoods.config;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@Configuration @Profile("!waiting")
@EntityScan("com.limitedgoods")
@ComponentScan(basePackages={"com.limitedgoods.sales","com.limitedgoods.purchases",
    "com.limitedgoods.payments","com.limitedgoods.reservations","com.limitedgoods.admission",
    "com.limitedgoods.worker","com.limitedgoods.mockpg"})
public class DomainConfig {}
