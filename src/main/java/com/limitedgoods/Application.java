package com.limitedgoods;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(scanBasePackages={"com.limitedgoods.config","com.limitedgoods.waiting"})
public class Application {
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
