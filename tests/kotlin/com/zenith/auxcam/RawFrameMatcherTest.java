package com.zenith.auxcam;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

/** Runs the production ownership/pairing policy, without Android framework mocks. */
public class RawFrameMatcherTest {
    private static class Frame implements AutoCloseable {
        final int id;
        int closes;
        Frame(int id) { this.id = id; }
        @Override public void close() { closes++; }
    }
    private static void rejects(Runnable action) {
        try { action.run(); fail("Expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void validatesSequenceCount() {
        for (int count : new int[]{Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE})
            rejects(() -> new RawFrameMatcher<Frame, String>(count));
        for (int count = 1; count <= 4; count++) new RawFrameMatcher<Frame, String>(count).close();
    }
    @Test public void imageBeforeResultTransfersExactMetadataAndIndex() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(3);
        Frame image = new Frame(2);
        assertNull(matcher.image(900L, 32L, image));
        RawFrameMatcher.Match<Frame, String> pair = matcher.result(900L, 2, "frame-two");
        assertSame(image, pair.image); assertEquals("frame-two", pair.result);
        assertEquals(2, pair.index); assertEquals(900L, pair.timestampNs);
        matcher.close(); assertEquals(0, image.closes);
        pair.image.close(); assertEquals(1, image.closes);
    }
    @Test public void resultBeforeImageTransfersExactMetadata() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
        assertNull(matcher.result(1000L, 0, "metadata"));
        Frame image = new Frame(0);
        RawFrameMatcher.Match<Frame, String> pair = matcher.image(1000L, 1L, image);
        assertSame(image, pair.image); assertEquals("metadata", pair.result);
        matcher.close(); assertEquals(0, image.closes); pair.image.close();
    }
    @Test public void timestampsNeverUseNearestOrLatestResult() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        Frame first = new Frame(1), second = new Frame(0);
        assertNull(matcher.image(10L, 1, first));
        assertNull(matcher.result(11L, 0, "second"));
        RawFrameMatcher.Match<Frame, String> b = matcher.image(11L, 1, second);
        RawFrameMatcher.Match<Frame, String> a = matcher.result(10L, 1, "first");
        assertSame(first, a.image); assertEquals("first", a.result); assertEquals(1, a.index);
        assertSame(second, b.image); assertEquals("second", b.result); assertEquals(0, b.index);
        matcher.close(); a.image.close(); b.image.close();
    }
    @Test public void shuffledCallbacksKeepEveryFrameUniqueAndCloseExactlyOnce() {
        Random random = new Random(0xD06);
        for (int trial = 0; trial < 500; trial++) {
            RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(4);
            Frame[] images = {new Frame(0), new Frame(1), new Frame(2), new Frame(3)};
            List<Integer> events = new ArrayList<>();
            for (int event = 0; event < 8; event++) events.add(event);
            Collections.shuffle(events, random);
            boolean[] delivered = new boolean[4];
            for (int event : events) {
                int index = event % 4;
                long timestamp = 100L + index * 3L;
                RawFrameMatcher.Match<Frame, String> pair = event < 4
                    ? matcher.image(timestamp, 8L, images[index])
                    : matcher.result(timestamp, index, "metadata-" + index);
                if (pair == null) continue;
                assertFalse(delivered[pair.index]); delivered[pair.index] = true;
                assertEquals(pair.index, pair.image.id);
                assertEquals("metadata-" + pair.index, pair.result);
                assertEquals(100L + pair.index * 3L, pair.timestampNs);
                pair.image.close();
            }
            matcher.close(); matcher.close();
            for (int index = 0; index < 4; index++) { assertTrue(delivered[index]); assertEquals(1, images[index].closes); }
        }
    }
    @Test public void duplicateTimestampClosesOnlyTheNewAcquisition() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        Frame first = new Frame(0), duplicate = new Frame(1);
        matcher.image(10, 1, first);
        rejects(() -> matcher.image(10, 1, duplicate));
        assertEquals(1, duplicate.closes); assertEquals(0, first.closes);
        matcher.close(); assertEquals(1, first.closes); assertEquals(1, duplicate.closes);
    }
    @Test public void repeatedObjectCannotInvalidateRetainedOrDeliveredOwnership() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        Frame image = new Frame(0);
        matcher.image(10, 1, image);
        rejects(() -> matcher.image(20, 1, image)); assertEquals(0, image.closes);
        RawFrameMatcher.Match<Frame, String> pair = matcher.result(10, 0, "own");
        rejects(() -> matcher.image(30, 1, image)); assertEquals(0, image.closes);
        pair.image.close(); matcher.close();
        rejects(() -> matcher.image(40, 1, image)); assertEquals(1, image.closes);
    }
    @Test public void duplicateResultTimestampOrIndexDoesNotStealMetadata() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        matcher.result(10, 0, "original");
        rejects(() -> matcher.result(10, 1, "duplicate-time"));
        rejects(() -> matcher.result(20, 0, "duplicate-index"));
        Frame image = new Frame(0);
        assertEquals("original", matcher.image(10, 1, image).result);
        matcher.close(); image.close();
    }
    @Test public void distinctUnmatchedImagesCloseOnCancellation() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(3);
        Frame a = new Frame(0), b = new Frame(1);
        matcher.image(10, 1, a); matcher.image(20, 1, b); matcher.result(30, 2, "unmatched");
        matcher.close(); matcher.close();
        assertEquals(1, a.closes); assertEquals(1, b.closes);
    }
    @Test public void failedCloseDoesNotLeakRemainingImages() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        Frame bad = new Frame(0) { @Override public void close() { super.close(); throw new IllegalStateException("close"); } };
        Frame good = new Frame(1);
        matcher.image(10, 1, bad); matcher.image(20, 1, good); matcher.close(); matcher.close();
        assertEquals(1, bad.closes); assertEquals(1, good.closes);
    }
    @Test public void payloadBudgetIncludesPreviouslyTransferredFrames() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        matcher.result(10, 0, "first");
        Frame first = new Frame(0), second = new Frame(1);
        matcher.image(10, RawFrameMatcher.MAX_BYTES, first).image.close();
        rejects(() -> matcher.image(20, 1, second));
        matcher.close(); assertEquals(1, first.closes); assertEquals(1, second.closes);
    }
    @Test public void exactBudgetBoundaryIsAcceptedButPaddingCanReject() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(2);
        Frame a = new Frame(0), b = new Frame(1);
        matcher.image(10, RawFrameMatcher.MAX_BYTES / 2, a);
        matcher.image(20, RawFrameMatcher.MAX_BYTES / 2, b);
        matcher.close(); assertEquals(1, a.closes); assertEquals(1, b.closes);
        RawFrameMatcher<Frame, String> padded = new RawFrameMatcher<>(2);
        Frame c = new Frame(2), d = new Frame(3);
        padded.image(10, RawFrameMatcher.MAX_BYTES / 2, c);
        rejects(() -> padded.image(20, RawFrameMatcher.MAX_BYTES / 2 + 1, d));
        padded.close(); assertEquals(1, c.closes); assertEquals(1, d.closes);
    }
    @Test public void invalidOrOverflowingImageInputsAreReleased() {
        for (long bytes : new long[]{Long.MIN_VALUE, -1L, 0L, RawFrameMatcher.MAX_BYTES + 1, Long.MAX_VALUE}) {
            RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
            Frame image = new Frame(0);
            rejects(() -> matcher.image(10, bytes, image)); matcher.close(); assertEquals(1, image.closes);
        }
        for (long timestamp : new long[]{Long.MIN_VALUE, -1L, 0L}) {
            RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
            Frame image = new Frame(0);
            rejects(() -> matcher.image(timestamp, 1, image)); matcher.close(); assertEquals(1, image.closes);
        }
    }
    @Test public void invalidResultInputsDoNotConsumeTheImage() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
        Frame image = new Frame(0); matcher.image(10, 1, image);
        rejects(() -> matcher.result(10, -1, "bad")); rejects(() -> matcher.result(10, 1, "bad"));
        rejects(() -> matcher.result(0, 0, "bad")); rejects(() -> matcher.result(10, 0, null));
        assertSame(image, matcher.result(10, 0, "good").image);
        matcher.close(); assertEquals(0, image.closes); image.close();
    }
    @Test public void frameCountBoundRejectsNewImagesAndReleasesThem() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
        Frame a = new Frame(0), excess = new Frame(1);
        matcher.image(10, 1, a); rejects(() -> matcher.image(20, 1, excess));
        matcher.close(); assertEquals(1, a.closes); assertEquals(1, excess.closes);
    }
    @Test public void closedMatcherRejectsFreshInputsWithoutDoubleReleasingAcceptedImages() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
        Frame old = new Frame(0), fresh = new Frame(1);
        matcher.image(10, 1, old); matcher.close();
        rejects(() -> matcher.image(10, 1, old)); rejects(() -> matcher.image(20, 1, fresh));
        rejects(() -> matcher.result(10, 0, "late")); matcher.close();
        assertEquals(1, old.closes); assertEquals(1, fresh.closes);
    }
    @Test public void nullAcquisitionIsRejectedWithoutAResourceLeak() {
        RawFrameMatcher<Frame, String> matcher = new RawFrameMatcher<>(1);
        rejects(() -> matcher.image(10, 1, null)); matcher.close();
    }
    @Test public void dimensionBudgetIsConservativeAndCannotOverflow() {
        assertEquals(4, RawFrameMatcher.maxFramesForSize(4080, 3072));
        assertEquals(1, RawFrameMatcher.maxFramesForSize(8160, 6144));
        assertEquals(1, RawFrameMatcher.maxFramesForSize(8192, 8192));
        assertEquals(0, RawFrameMatcher.maxFramesForSize(8193, 8192));
        assertEquals(0, RawFrameMatcher.maxFramesForSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(0, RawFrameMatcher.maxFramesForSize(0, 10));
        assertEquals(0, RawFrameMatcher.maxFramesForSize(-1, 10));
    }
    @Test public void dngOrientationUsesExifCodesRatherThanDegrees() {
        int[] degrees = {0, 90, 180, 270, 360, -90, 450, 540};
        int[] exif = {1, 6, 3, 8, 1, 8, 6, 3};
        for (int i = 0; i < degrees.length; i++) assertEquals(exif[i], RawFrameMatcher.exifOrientation(degrees[i]));
    }
}
