# Module Arrow Resilience Kit
Kotlin Multiplatform resilience patterns built on Arrow-kt. Coroutine-first, composable, and safe for concurrent use across JVM, JS, and Native (incl. iOS).

# Package com.sorinirmies.arrow.resiliencekit
Core resilience patterns: Bulkhead, Cache, CircuitBreaker (consecutive-failure and SlidingWindowCircuitBreaker failure-rate variants), RateLimiter, RetryRepeat (plus RetryBudget against retry storms), Saga, TimeLimiter, AdaptiveLimiter, Hedge, Failover (with active health-probing), and Chaos (fault-injection test helpers). Each with DSL builders and named registries. `Policy` composes patterns into reusable chains; `FlowResilience` provides `Flow`-native counterparts. `SharedStateStore` is an extension point for distributing state across instances. `MicrometerBridge` (JVM-only, optional) exports statistics as Micrometer gauges.

# Package com.sorinirmies.arrow.resiliencekit.stm
Lock-free composable transactional primitives built on Arrow STM: StmCounter, StmGauge, StmStateMachine, StmSemaphore, StmRateWindow, and TVar extension functions.
