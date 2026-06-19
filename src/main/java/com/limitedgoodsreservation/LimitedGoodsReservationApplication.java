package com.limitedgoodsreservation;

import com.limitedgoodsreservation.stock.strategy.StockStrategyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(StockStrategyProperties.class)
public class LimitedGoodsReservationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LimitedGoodsReservationApplication.class, args);
    }
}
