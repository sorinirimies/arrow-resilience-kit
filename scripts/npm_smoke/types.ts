// Type-check only (tsc --noEmit): the published .d.ts must describe the real API.
import { CircuitBreaker, Logging, Retry, type RetryOptions } from "arrow-resilience-kit";

export async function demo(signal: AbortSignal): Promise<string> {
    const options: RetryOptions = {
        retries: 5,
        maxDelayMillis: 10_000,
        shouldRetry: (e: { status?: number }) => e.status === 429,
        delayMillis: () => undefined,
        onRetry: (_e, retry: number, delay: number) => void [retry, delay],
        signal,
    };
    const breaker = await CircuitBreaker.create(5, 30_000);
    Logging.setLevel("WARN");
    const level: string = Logging.level();
    return breaker.execute(() => Retry.withBackoff(() => Promise.resolve(level), options), signal);
}
