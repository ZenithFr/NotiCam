package com.zenith.auxcam;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Handler-confined, bounded RAW ownership and exact timestamp pairing. No Android dependencies.
 * An offered image is owned here until returned in a Match; the consumer then owns it.
 * Each acquisition must be offered once. Rejected distinct acquisitions and unmatched
 * images at cancellation are closed here. Re-offering an accepted object never closes
 * it again, including after ownership has passed to a writer.
 */
final class RawFrameMatcher<I extends AutoCloseable, R> implements AutoCloseable {
    static final int MAX_FRAMES = 4;
    static final long MAX_BYTES = 128L * 1024 * 1024;
    static final class Match<I, R> {
        final I image;
        final R result;
        final int index;
        final long timestampNs;
        Match(I image, R result, int index, long timestampNs) {
            this.image = image; this.result = result; this.index = index; this.timestampNs = timestampNs;
        }
    }
    private static final class Result<R> {
        final int index; final R value;
        Result(int index, R value) { this.index = index; this.value = value; }
    }
    private final int count;
    private final Map<Long, I> images = new HashMap<>();
    private final Map<Long, Result<R>> results = new HashMap<>();
    private final Set<Long> imageTimes = new HashSet<>(), resultTimes = new HashSet<>();
    private final Set<Integer> indices = new HashSet<>();
    // Bounded by count; keep identities even after close to prevent a second release.
    private final Set<I> acceptedImages = Collections.newSetFromMap(new IdentityHashMap<>());
    private long offeredBytes;
    private boolean closed;

    RawFrameMatcher(int count) {
        if (count < 1 || count > MAX_FRAMES) throw new IllegalArgumentException("RAW sequence must contain 1–4 frames");
        this.count = count;
    }
    Match<I, R> image(long timestampNs, long bytes, I image) {
        if (acceptedImages.contains(image)) throw new IllegalArgumentException("RAW acquisition offered twice");
        try {
            if (closed || image == null || timestampNs <= 0 || bytes <= 0 || bytes > MAX_BYTES - offeredBytes ||
                    imageTimes.size() >= count || imageTimes.contains(timestampNs))
                throw new IllegalArgumentException("Invalid, duplicate or over-budget RAW image");
            acceptedImages.add(image);
            imageTimes.add(timestampNs);
            offeredBytes += bytes;
            Result<R> result = results.remove(timestampNs);
            if (result != null) return new Match<>(image, result.value, result.index, timestampNs);
            images.put(timestampNs, image);
            return null;
        } catch (RuntimeException failure) {
            release(image);
            throw failure;
        }
    }
    Match<I, R> result(long timestampNs, int index, R result) {
        if (closed || result == null || timestampNs <= 0 || index < 0 || index >= count ||
                indices.contains(index) || resultTimes.contains(timestampNs))
            throw new IllegalArgumentException("Invalid or duplicate RAW capture result");
        resultTimes.add(timestampNs); indices.add(index);
        I image = images.remove(timestampNs);
        if (image != null) return new Match<>(image, result, index, timestampNs);
        results.put(timestampNs, new Result<>(index, result));
        return null;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        for (I image : images.values()) release(image);
        images.clear(); results.clear(); imageTimes.clear(); resultTimes.clear(); indices.clear();
    }
    /** Conservative payload estimate; actual plane padding is enforced by image(). */
    static int maxFramesForSize(int width, int height) {
        if (width <= 0 || height <= 0) return 0;
        // Check before multiplying: malicious/invalid int dimensions cannot overflow.
        long pixels = (long) width * height;
        if (pixels > MAX_BYTES / 2) return 0;
        return (int) Math.min(MAX_FRAMES, MAX_BYTES / (pixels * 2));
    }
    static int exifOrientation(int sensorDegrees) {
        switch ((sensorDegrees % 360 + 360) % 360) {
            case 90: return 6;
            case 180: return 3;
            case 270: return 8;
            default: return 1;
        }
    }
    private static void release(AutoCloseable image) {
        if (image != null) try { image.close(); } catch (Exception ignored) { }
    }
}
