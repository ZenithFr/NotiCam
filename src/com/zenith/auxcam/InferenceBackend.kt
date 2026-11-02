package com.zenith.auxcam

/** Try discovered NNAPI accelerators, then system selection, then an explicit CPU.
 * Validation includes interpreter creation, tensor checks and a warmup, not just
 * constructing a delegate. A rejected backend is always closed before retrying.
 */
internal object InferenceBackend {
    fun <T : AutoCloseable> open(devices: List<String>, accelerated: (String?) -> T,
                               cpu: () -> T, validate: (T) -> Unit): T {
        for (device in devices.distinct().take(3) + listOf<String?>(null)) {
            var candidate: T? = null
            try {
                candidate = accelerated(device)
                validate(candidate)
                return candidate
            } catch (failure: Throwable) {
                try { candidate?.close() } catch (_: Exception) { }
                if (failure !is Exception && failure !is LinkageError) throw failure
            }
        }
        val fallback = cpu()
        try { validate(fallback); return fallback }
        catch (failure: Throwable) { try { fallback.close() } catch (_: Exception) { }; throw failure }
    }
}

/** Names come from the platform NNAPI device list, never a guessed MediaTek name. */
internal object NnapiDevices {
    private external fun acceleratorNames(): Array<String>
    fun available(): List<String> = try { acceleratorNames().toList() }
        catch (_: LinkageError) { emptyList() }
}
