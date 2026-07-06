package com.limitedgoodsreservation;

import com.limitedgoodsreservation.stock.strategy.StockStrategyProperties;
import com.limitedgoodsreservation.waitingroom.service.WaitingRoomProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({StockStrategyProperties.class, WaitingRoomProperties.class})
public class LimitedGoodsReservationApplication {

    public static void main(String[] args) {
        SpringApplication.run(LimitedGoodsReservationApplication.class, args);
    }
}
