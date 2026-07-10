package com.limitedgoodsreservation.purchase.service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PurchaseFlowSelector {

    private final PurchaseArchitectureProperties properties;
    private final List<PurchaseFlow> flows;
    private PurchaseFlow selectedFlow;

    public PurchaseFlowSelector(PurchaseArchitectureProperties properties, List<PurchaseFlow> flows) {
        this.properties = properties;
        this.flows = flows;
    }

    @PostConstruct
    void initialize() {
        String configuredArchitecture = properties.selectedArchitecture();
        selectedFlow = flows.stream()
                .filter(flow -> flow.supports(configuredArchitecture))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown purchase architecture: " + configuredArchitecture
                                + ". Available architectures: " + availableArchitectures()
                ));
    }

    public PurchaseFlow selectedFlow() {
        return selectedFlow;
    }

    public String selectedMetricsName() {
        return selectedFlow.metricsName();
    }

    private String availableArchitectures() {
        return flows.stream()
                .map(PurchaseFlow::metricsName)
                .sorted()
                .toList()
                .toString();
    }
}
