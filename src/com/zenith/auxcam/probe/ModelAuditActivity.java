package com.zenith.auxcam.probe;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class ModelAuditActivity extends Activity {
    private static final String TAG = "ModelAudit";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        Log.i(TAG, "=== Starting ModelAuditActivity in UID=" + android.os.Process.myUid() + " ===");

        new Thread(new AuditRunnable(this)).start();
    }

    static class AuditRunnable implements Runnable {
        private final ModelAuditActivity activity;

        AuditRunnable(ModelAuditActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            runTest2c_MDLA();
            runTest2e_MDLA();
        }

        private void runTest2e_MDLA() {
            try {
                Log.i(TAG, "=======================================================");
                Log.i(TAG, "=== Running Test 2e: Real Architecture Candidate (MDLA INT8) ===");
                ByteBuffer modelBuf = activity.loadModelFile("models/portrait_v1_candidate.tflite");
                if (modelBuf == null) {
                    Log.e("Audit2e", "Failed to load models/portrait_v1_candidate.tflite");
                    return;
                }

                NnApiDelegate.Options nnapiOpts = new NnApiDelegate.Options();
                nnapiOpts.setAcceleratorName("mtk-mdla_shim");
                NnApiDelegate nnapiDelegate = new NnApiDelegate(nnapiOpts);

                Interpreter.Options opts = new Interpreter.Options();
                opts.setNumThreads(2);
                opts.addDelegate(nnapiDelegate);

                Log.i("Audit2e", "Initializing Interpreter targeting mtk-mdla_shim...");
                Interpreter interp = new Interpreter(modelBuf, opts);
                Log.i("Audit2e", "Interpreter initialized successfully on mtk-mdla_shim!");

                byte[][][][] in = new byte[1][384][512][3];
                byte[][][][] out = new byte[1][96][128][2];

                // Warm up — 15 runs
                Log.i("Audit2e", "Running 15 warmup inferences...");
                for (int i = 0; i < 15; i++) {
                    interp.run(in, out);
                }

                // Measure 10 steady-state runs
                Log.i("Audit2e", "Measuring 10 steady-state inferences...");
                long[] times = new long[10];
                for (int i = 0; i < 10; i++) {
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    interp.run(in, out);
                    long dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000;
                    times[i] = dt;
                    Log.i("Audit2e", "MDLA INT8 candidate run #" + i + ": " + dt + " ms");
                }

                Arrays.sort(times);
                Log.i("Audit2e", "=======================================================");
                Log.i("Audit2e", "portrait_v1_candidate steady-state p50=" + times[5] + "ms p95=" + times[9] + "ms");
                Log.i("Audit2e", "sorted times: " + Arrays.toString(times));
                Log.i("Audit2e", "=======================================================");

                interp.close();
                nnapiDelegate.close();
            } catch (Throwable t) {
                Log.e("Audit2e", "Test 2e failed", t);
            }
        }

        private void runTest2c_MDLA() {
            try {
                Log.i(TAG, "=======================================================");
                Log.i(TAG, "=== Running Test 2c: Realistic Full-Model Proxy (MDLA INT8) ===");
                ByteBuffer modelBuf = activity.loadModelFile("models/synthetic_full_int8.tflite");
                if (modelBuf == null) {
                    Log.e("Audit2c", "Failed to load models/synthetic_full_int8.tflite");
                    return;
                }

                NnApiDelegate.Options nnapiOpts = new NnApiDelegate.Options();
                nnapiOpts.setAcceleratorName("mtk-mdla_shim");
                NnApiDelegate nnapiDelegate = new NnApiDelegate(nnapiOpts);

                Interpreter.Options opts = new Interpreter.Options();
                opts.setNumThreads(2);
                opts.addDelegate(nnapiDelegate);

                Log.i("Audit2c", "Initializing Interpreter targeting mtk-mdla_shim...");
                Interpreter interp = new Interpreter(modelBuf, opts);
                Log.i("Audit2c", "Interpreter initialized successfully on mtk-mdla_shim!");

                byte[][][][] in = new byte[1][384][512][3];
                byte[][][][] out = new byte[1][96][128][2];

                // Warm up — 10 runs
                Log.i("Audit2c", "Running 10 warmup inferences...");
                for (int i = 0; i < 10; i++) {
                    interp.run(in, out);
                }

                // Measure 10 steady-state runs
                Log.i("Audit2c", "Measuring 10 steady-state inferences...");
                long[] times = new long[10];
                for (int i = 0; i < 10; i++) {
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    interp.run(in, out);
                    long dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000;
                    times[i] = dt;
                    Log.i("Audit2c", "MDLA INT8 run #" + i + ": " + dt + " ms");
                }

                Arrays.sort(times);
                Log.i("Audit2c", "=======================================================");
                Log.i("Audit2c", "synthetic_full_int8 steady-state p50=" + times[5] + "ms p95=" + times[9] + "ms");
                Log.i("Audit2c", "sorted times: " + Arrays.toString(times));
                Log.i("Audit2c", "=======================================================");

                interp.close();
                nnapiDelegate.close();
            } catch (Throwable t) {
                Log.e("Audit2c", "Test 2c failed", t);
            }
        }

        private void runTest2d_GPU() {
            try {
                Log.i(TAG, "=======================================================");
                Log.i(TAG, "=== Running Test 2d: GPU Delegate Reference (Mali-G615) ===");
                ByteBuffer modelBuf = activity.loadModelFile("models/synthetic_full_fp16.tflite");
                if (modelBuf == null) {
                    Log.e("Audit2d", "Failed to load models/synthetic_full_fp16.tflite");
                    return;
                }

                GpuDelegate gpuDelegate = new GpuDelegate();
                Interpreter.Options opts = new Interpreter.Options();
                opts.addDelegate(gpuDelegate);

                Log.i("Audit2d", "Initializing Interpreter with GpuDelegate...");
                Interpreter interp = new Interpreter(modelBuf, opts);
                Log.i("Audit2d", "Interpreter initialized successfully on GpuDelegate!");

                float[][][][] in = new float[1][384][512][3];
                float[][][][] out = new float[1][96][128][2];

                // Warm up — 10 runs
                Log.i("Audit2d", "Running 10 warmup inferences...");
                for (int i = 0; i < 10; i++) {
                    interp.run(in, out);
                }

                // Measure 10 steady-state runs
                Log.i("Audit2d", "Measuring 10 steady-state inferences...");
                long[] times = new long[10];
                for (int i = 0; i < 10; i++) {
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    interp.run(in, out);
                    long dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000;
                    times[i] = dt;
                    Log.i("Audit2d", "GPU FP16 run #" + i + ": " + dt + " ms");
                }

                Arrays.sort(times);
                Log.i("Audit2d", "=======================================================");
                Log.i("Audit2d", "synthetic_full_gpu steady-state p50=" + times[5] + "ms p95=" + times[9] + "ms");
                Log.i("Audit2d", "sorted times: " + Arrays.toString(times));
                Log.i("Audit2d", "=======================================================");

                interp.close();
                gpuDelegate.close();
            } catch (Throwable t) {
                Log.e("Audit2d", "Test 2d failed", t);
            }
        }
    }

    ByteBuffer loadModelFile(String assetPath) {
        try {
            AssetFileDescriptor fileDescriptor = getAssets().openFd(assetPath);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } catch (Throwable t) {
            Log.w(TAG, "openFd failed (" + t.getMessage() + "), falling back to direct stream read");
            try (java.io.InputStream is = getAssets().open(assetPath)) {
                byte[] bytes = new byte[is.available()];
                int read = 0;
                while (read < bytes.length) {
                    int r = is.read(bytes, read, bytes.length - read);
                    if (r == -1) break;
                    read += r;
                }
                ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length);
                buf.order(ByteOrder.nativeOrder());
                buf.put(bytes);
                buf.rewind();
                return buf;
            } catch (Throwable t2) {
                Log.e(TAG, "Error loading asset " + assetPath, t2);
                return null;
            }
        }
    }
}
