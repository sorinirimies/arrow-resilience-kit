# Module Arrow Resilience Kit

A comprehensive resilience patterns library for Kotlin Multiplatform using Arrow-kt.

This library provides production-ready implementations of common resilience patterns including retry, circuit breaker, bulkhead, rate limiter, time limiter, cache, and saga patterns.

## Resilience Patterns

The library includes the following resilience patterns:

- Retry and Repeat: Automatic retry with exponential backoff, linear backoff, and custom strategies
- Circuit Breaker: Prevent cascading failures with configurable thresholds and half-open states
- Bulkhead: Resource isolation and concurrency limiting using semaphores
- Rate Limiter: Control request rates with token bucket algorithm
- Time Limiter: Timeout handling with fallback support
- Cache: Thread-safe caching with TTL and size limits
- Saga: Distributed transaction coordination with automatic compensation

All patterns are implemented using Arrow's functional programming constructs and Software Transactional Memory (STM) for safe concurrent access.