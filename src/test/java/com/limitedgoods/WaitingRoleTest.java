package com.limitedgoods;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=waiting","spring.datasource.url=jdbc:postgresql://127.0.0.1:1/must_not_connect"})
class WaitingRoleTest {
    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate http;
    @Test void waitingStartsWithoutDatabaseAndDoesNotExposeCheckoutRoutes() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
        assertThat(http.getForEntity("/actuator/health/readiness",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/orders/00000000-0000-0000-0000-000000000000",String.class).getStatusCode().value()).isEqualTo(404);
    }
}
