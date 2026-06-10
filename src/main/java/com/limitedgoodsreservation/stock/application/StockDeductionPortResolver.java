package com.limitedgoodsreservation.stock.application;

import com.limitedgoodsreservation.stock.application.port.StockDeductionPort;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StockDeductionPortResolver {

    private final StockStrategyProperties properties;
    private final List<StockDeductionPort> ports;
    private StockDeductionPort selectedPort;

    public StockDeductionPortResolver(
            StockStrategyProperties properties,
            List<StockDeductionPort> ports
    ) {
        this.properties = properties;
        this.ports = ports;
    }

    @PostConstruct
    void initialize() {
        String selectedStrategy = properties.selectedStrategy();
        selectedPort = ports.stream()
                .filter(port -> port.strategyName().equals(selectedStrategy))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown stock strategy: " + selectedStrategy + ". Available strategies: " + availableStrategies()
                ));
    }

    public StockDeductionPort selectedPort() {
        return selectedPort;
    }

    public String selectedStrategyName() {
        return selectedPort.strategyName();
    }

    private String availableStrategies() {
        return ports.stream()
                .map(StockDeductionPort::strategyName)
                .sorted()
                .toList()
                .toString();
    }
}
