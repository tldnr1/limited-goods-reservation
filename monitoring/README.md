# Monitoring

Docker Compose starts the v2 experiment monitoring stack:

```text
Prometheus: http://localhost:9090
Grafana: http://localhost:3000
Grafana login: admin / admin
Dashboard: Limited Goods / v2 Stock Strategy Overview
```

Prometheus scrapes Spring Boot metrics from:

```text
http://api:8080/actuator/prometheus
```

k6 writes load-test metrics through Prometheus remote write when the load-test profile runs.

Use the Grafana dashboard for troubleshooting screenshots, and keep the DB verification query output in the experiment record.

The v2 dashboard is intended to show:

```text
purchase success/failure counts by strategy
failure reasons
k6 request rate
k6 p50/p95/p99 latency
API status rates
DB connection pool pressure
JVM/process signals
```
