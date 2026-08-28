project: TradingSagaTCC
language: Java 21
framework: Spring Boot 3.2.2
database_dev: H2 (dual: orderdb + accountdb)
database_prod: N/A (teaching)
test: JUnit 5 + MockMvc + Embedded Kafka
coverage_target: 80
logging: Logback
api_docs: springdoc-openapi
backend_port: 8093
frontend_port: 8093
scheduler:
  pool_size: 2
  thread_name_prefix: saga-sched-
ai_tools:
  - Cursor
eos_version: 0.1.13
