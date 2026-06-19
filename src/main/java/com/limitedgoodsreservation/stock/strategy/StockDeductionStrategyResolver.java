package com.limitedgoodsreservation.stock.strategy;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StockDeductionStrategyResolver {

    private final StockStrategyProperties properties;
    private final List<StockDeductionStrategy> strategies;
    private StockDeductionStrategy selectedStrategy;

    public StockDeductionStrategyResolver(
            StockStrategyProperties properties,
            List<StockDeductionStrategy> strategies
    ) {
        this.properties = properties;
        this.strategies = strategies;
    }

    @PostConstruct
    void initialize() {
        String configuredStrategy = properties.selectedStrategy();
        selectedStrategy = strategies.stream()
                .filter(strategy -> strategy.strategyName().equals(configuredStrategy))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown stock strategy: " + configuredStrategy + ". Available strategies: " + availableStrategies()
                ));
    }

    public StockDeductionStrategy selectedStrategy() {
        return selectedStrategy;
    }

    public String selectedStrategyName() {
        return selectedStrategy.strategyName();
    }

    private String availableStrategies() {
        return strategies.stream()
                .map(StockDeductionStrategy::strategyName)
                .sorted()
                .toList()
                .toString();
    }
}
