
// ---- Appended by build.gradle.kts (finalizeNpmPackage): types the Kotlin/JS d.ts generator can't emit. ----

/** Options for `Retry.withBackoff` -- every field is optional. */
export interface RetryOptions {
    /** Maximum retries after the first attempt. Default 3. */
    readonly retries?: number;
    /** First backoff delay in ms. Default 200. */
    readonly baseMillis?: number;
    /** Backoff growth per retry. Default 2. */
    readonly factor?: number;
    /** Cap for the computed backoff in ms. Default: none. */
    readonly maxDelayMillis?: number;
    /** Random spread of the computed backoff, 0..1. Default 0.1 (±10%). */
    readonly jitter?: number;
    /** Retry this error? Gets the error exactly as your block rejected with it. Default: always. */
    readonly shouldRetry?: (error: any, retry: number) => boolean;
    /** Exact delay in ms for this error (e.g. from a `Retry-After` header); null/undefined = use the backoff. */
    readonly delayMillis?: (error: any, retry: number) => number | null | undefined;
    /** Called before each wait. */
    readonly onRetry?: (error: any, retry: number, delayMillis: number) => void;
    /** Aborting cancels the current attempt's wait and rejects with `signal.reason`. */
    readonly signal?: AbortSignal;
}
