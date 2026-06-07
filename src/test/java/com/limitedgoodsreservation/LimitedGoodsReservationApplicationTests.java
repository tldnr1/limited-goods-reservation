package com.limitedgoodsreservation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class LimitedGoodsReservationApplicationTests {

    @Test
    void contextLoads() {
    }
}
