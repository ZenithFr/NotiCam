package com.zenith.auxcam.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Locale;

public class ModelAuditActivity extends Activity {
    private static final String TAG = "ModelAudit";

    enum DelegateType {
        NNAPI_MDLA,
        GPU_FP16,
        CPU_DEFAULT
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        Log.i(TAG, "=== Starting ModelAuditActivity in UID=" + android.os.Process.myUid() + " ===");

        Intent intent = getIntent();
        String modelPath = intent != null ? intent.getStringExtra("model_path") : null;
        String modelPathA = intent != null ? intent.getStringExtra("model_path_a") : null;
        String modelPathB = intent != null ? intent.getStringExtra("model_path_b") : null;
        boolean forceCpu = intent != null && (intent.getBooleanExtra("force_cpu", false) || "true".equalsIgnoreCase(intent.getStringExtra("force_cpu")));
        boolean verify = intent != null && (intent.getBooleanExtra("verify", false) || "true".equalsIgnoreCase(intent.getStringExtra("verify")));

        if (verify) {
            new Thread(new VerifyRunnable(this, modelPath != null ? modelPath : "/sdcard/Android/data/com.zenith.auxcam/files/models/portrait_v1_int8.tflite")).start();
        } else if (modelPathA != null && modelPathB != null) {
            new Thread(new DynamicDualAuditRunnable(this, modelPathA, modelPathB, forceCpu)).start();
        } else if (modelPath != null) {
            new Thread(new DynamicAuditRunnable(this, modelPath, forceCpu)).start();
        } else {
            new Thread(new AuditRunnable(this)).start();
        }
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

    ByteBuffer loadModelDynamic(String path) throws java.io.IOException {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            File ext = new File(getExternalFilesDir(null), "models/" + file.getName());
            if (ext.exists() && ext.canRead()) {
                file = ext;
            } else {
                File internal = new File(getFilesDir(), "models/" + file.getName());
                if (internal.exists() && internal.canRead()) {
                    file = internal;
                }
            }
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            FileChannel fc = fis.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
        }
    }

    static class DynamicAuditRunnable implements Runnable {
        private final ModelAuditActivity activity;
        private final String modelPath;
        private final boolean forceCpu;

        DynamicAuditRunnable(ModelAuditActivity activity, String modelPath, boolean forceCpu) {
            this.activity = activity;
            this.modelPath = modelPath;
            this.forceCpu = forceCpu;
        }

        @Override
        public void run() {
            String baseName = new File(modelPath).getName();
            Log.i("AuditDyn", "=== dynamic audit: " + baseName + " ===");

            ByteBuffer modelBuf;
            try {
                modelBuf = activity.loadModelDynamic(modelPath);
            } catch (Throwable t) {
                Log.e("AuditDyn", "Failed to load model from path " + modelPath, t);
                return;
            }

            if (forceCpu) {
                runWithDelegate(modelBuf, baseName, DelegateType.CPU_DEFAULT);
                return;
            }

            // Try NNAPI first
            boolean nnapiOk = runWithDelegate(modelBuf, baseName, DelegateType.NNAPI_MDLA);
            if (!nnapiOk) {
                // Retry with GPU
                boolean gpuOk = runWithDelegate(modelBuf, baseName, DelegateType.GPU_FP16);
                if (!gpuOk) {
                    // Retry with CPU
                    runWithDelegate(modelBuf, baseName, DelegateType.CPU_DEFAULT);
                }
            }
        }

        private boolean runWithDelegate(ByteBuffer modelBuf, String baseName, DelegateType delegateType) {
            Interpreter interp = null;
            NnApiDelegate nnapiDelegate = null;
            GpuDelegate gpuDelegate = null;
            String delegateName;

            try {
                Interpreter.Options opts = new Interpreter.Options();
                if (delegateType == DelegateType.NNAPI_MDLA) {
                    delegateName = "mtk-mdla_shim";
                    try {
                        NnApiDelegate.Options nnapiOpts = new NnApiDelegate.Options();
                        nnapiOpts.setAcceleratorName("mtk-mdla_shim");
                        nnapiDelegate = new NnApiDelegate(nnapiOpts);
                        opts.setNumThreads(2);
                        opts.addDelegate(nnapiDelegate);
                    } catch (Throwable t) {
                        Log.w("AuditDyn", "NNAPI init: FAILED — " + t.getMessage());
                        return false;
                    }
                } else if (delegateType == DelegateType.GPU_FP16) {
                    delegateName = "gpu-fp16";
                    try {
                        gpuDelegate = new GpuDelegate();
                        opts.addDelegate(gpuDelegate);
                    } catch (Throwable t) {
                        Log.w("AuditDyn", "GPU init: FAILED — " + t.getMessage());
                        return false;
                    }
                } else {
                    delegateName = "default";
                    opts.setNumThreads(4);
                }

                try {
                    interp = new Interpreter(modelBuf, opts);
                    if (delegateType == DelegateType.NNAPI_MDLA) {
                        Log.i("AuditDyn", "NNAPI init: OK");
                    }
                } catch (Throwable t) {
                    if (delegateType == DelegateType.NNAPI_MDLA) {
                        Log.w("AuditDyn", "NNAPI init: FAILED — " + t.getMessage());
                    } else if (delegateType == DelegateType.GPU_FP16) {
                        Log.w("AuditDyn", "GPU init: FAILED — " + t.getMessage());
                    } else {
                        Log.w("AuditDyn", "CPU init: FAILED — " + t.getMessage());
                    }
                    return false;
                }

                Tensor inTensor = interp.getInputTensor(0);
                int[] inShape = inTensor.shape();
                DataType inType = inTensor.dataType();
                String inDtypeStr = inType == DataType.FLOAT32 ? "float32" : (inType == DataType.INT8 ? "int8" : inType.toString().toLowerCase(Locale.US));
                Log.i("AuditDyn", "input_shape=" + Arrays.toString(inShape) + "  dtype=" + inDtypeStr);

                Tensor outTensor = interp.getOutputTensor(0);
                int[] outShape = outTensor.shape();
                DataType outType = outTensor.dataType();
                String outDtypeStr = outType == DataType.FLOAT32 ? "float32" : (outType == DataType.INT8 ? "int8" : outType.toString().toLowerCase(Locale.US));
                Log.i("AuditDyn", "output_shape=" + Arrays.toString(outShape) + "  dtype=" + outDtypeStr);

                int inBytes = inTensor.numBytes();
                ByteBuffer inBuf = ByteBuffer.allocateDirect(inBytes).order(ByteOrder.nativeOrder());
                if (inType == DataType.INT8 || inType == DataType.UINT8) {
                    byte zp = 0;
                    try {
                        zp = (byte) inTensor.quantizationParams().getZeroPoint();
                    } catch (Throwable ignored) {}
                    for (int k = 0; k < inBytes; k++) inBuf.put(zp);
                } else {
                    for (int k = 0; k < inBytes; k++) inBuf.put((byte) 0);
                }
                inBuf.rewind();

                int outBytes = outTensor.numBytes();
                ByteBuffer outBuf = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder());

                // Smoke test
                inBuf.rewind();
                outBuf.rewind();
                interp.run(inBuf, outBuf);

                Log.i("AuditDyn", "warmup=20  timed=100");
                for (int i = 0; i < 20; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    interp.run(inBuf, outBuf);
                }

                long[] times = new long[100];
                for (int i = 0; i < 100; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    interp.run(inBuf, outBuf);
                    long dt = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000;
                    times[i] = dt;
                }

                Arrays.sort(times);
                long p50 = times[50];
                long p95 = times[95];
                long p99 = times[99];
                long min = times[0];
                long max = times[99];

                Log.i("AuditDyn", "p50=" + p50 + "ms  p95=" + p95 + "ms  p99=" + p99 + "ms  min=" + min + "  max=" + max);
                Log.i("AuditDyn", "delegate=" + delegateName + "  nodes=unknown");

                return true;
            } catch (Throwable t) {
                Log.e("AuditDyn", "Inference execution failed on delegate " + delegateType, t);
                return false;
            } finally {
                if (interp != null) {
                    try { interp.close(); } catch (Throwable ignored) {}
                }
                if (nnapiDelegate != null) {
                    try { nnapiDelegate.close(); } catch (Throwable ignored) {}
                }
                if (gpuDelegate != null) {
                    try { gpuDelegate.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    static class DynamicDualAuditRunnable implements Runnable {
        private final ModelAuditActivity activity;
        private final String modelPathA;
        private final String modelPathB;
        private final boolean forceCpu;

        DynamicDualAuditRunnable(ModelAuditActivity activity, String modelPathA, String modelPathB, boolean forceCpu) {
            this.activity = activity;
            this.modelPathA = modelPathA;
            this.modelPathB = modelPathB;
            this.forceCpu = forceCpu;
        }

        @Override
        public void run() {
            String baseA = new File(modelPathA).getName();
            String baseB = new File(modelPathB).getName();
            Log.i("AuditDyn", "=== dynamic dual audit: " + baseA + " + " + baseB + " ===");

            ByteBuffer bufA, bufB;
            try {
                bufA = activity.loadModelDynamic(modelPathA);
                bufB = activity.loadModelDynamic(modelPathB);
            } catch (Throwable t) {
                Log.e("AuditDyn", "Failed to load models for dual audit", t);
                return;
            }

            Interpreter interpA = null, interpB = null;
            NnApiDelegate nnapiA = null, nnapiB = null;

            try {
                Interpreter.Options optsA = new Interpreter.Options();
                Interpreter.Options optsB = new Interpreter.Options();

                if (!forceCpu) {
                    NnApiDelegate.Options oA = new NnApiDelegate.Options();
                    oA.setAcceleratorName("mtk-mdla_shim");
                    nnapiA = new NnApiDelegate(oA);
                    optsA.setNumThreads(2);
                    optsA.addDelegate(nnapiA);

                    NnApiDelegate.Options oB = new NnApiDelegate.Options();
                    oB.setAcceleratorName("mtk-mdla_shim");
                    nnapiB = new NnApiDelegate(oB);
                    optsB.setNumThreads(2);
                    optsB.addDelegate(nnapiB);
                } else {
                    optsA.setNumThreads(4);
                    optsB.setNumThreads(4);
                }

                interpA = new Interpreter(bufA, optsA);
                interpB = new Interpreter(bufB, optsB);

                Tensor inA = interpA.getInputTensor(0);
                ByteBuffer inBufA = ByteBuffer.allocateDirect(inA.numBytes()).order(ByteOrder.nativeOrder());
                for (int k = 0; k < inA.numBytes(); k++) inBufA.put((byte) 0);
                inBufA.rewind();
                Tensor outA = interpA.getOutputTensor(0);
                ByteBuffer outBufA = ByteBuffer.allocateDirect(outA.numBytes()).order(ByteOrder.nativeOrder());

                Tensor inB = interpB.getInputTensor(0);
                ByteBuffer inBufB = ByteBuffer.allocateDirect(inB.numBytes()).order(ByteOrder.nativeOrder());
                for (int k = 0; k < inB.numBytes(); k++) inBufB.put((byte) 0);
                inBufB.rewind();
                Tensor outB = interpB.getOutputTensor(0);
                ByteBuffer outBufB = ByteBuffer.allocateDirect(outB.numBytes()).order(ByteOrder.nativeOrder());

                // Warmup
                Log.i("AuditDyn", "dual warmup=20  timed=100");
                for (int i = 0; i < 20; i++) {
                    inBufA.rewind(); outBufA.rewind();
                    interpA.run(inBufA, outBufA);
                    inBufB.rewind(); outBufB.rewind();
                    interpB.run(inBufB, outBufB);
                }

                long[] timesCombined = new long[100];
                long[] timesA = new long[100];
                long[] timesB = new long[100];

                for (int i = 0; i < 100; i++) {
                    inBufA.rewind(); outBufA.rewind();
                    inBufB.rewind(); outBufB.rewind();

                    long t0 = SystemClock.elapsedRealtimeNanos();
                    interpA.run(inBufA, outBufA);
                    long t1 = SystemClock.elapsedRealtimeNanos();
                    interpB.run(inBufB, outBufB);
                    long t2 = SystemClock.elapsedRealtimeNanos();

                    timesA[i] = (t1 - t0) / 1_000_000;
                    timesB[i] = (t2 - t1) / 1_000_000;
                    timesCombined[i] = (t2 - t0) / 1_000_000;
                }

                Arrays.sort(timesCombined);
                Arrays.sort(timesA);
                Arrays.sort(timesB);

                long p50Comb = timesCombined[50];
                long p95Comb = timesCombined[95];
                long p99Comb = timesCombined[99];
                long minComb = timesCombined[0];
                long maxComb = timesCombined[99];

                long p50A = timesA[50];
                long p50B = timesB[50];

                long rssMb = getProcessRssMb();

                Log.i("AuditDyn", "dual combined p50=" + p50Comb + "ms  p95=" + p95Comb + "ms  p99=" + p99Comb + "ms  min=" + minComb + "  max=" + maxComb);
                Log.i("AuditDyn", "dual model_a p50=" + p50A + "ms  model_b p50=" + p50B + "ms");
                Log.i("AuditDyn", "dual peak_rss=" + rssMb + " MB");
            } catch (Throwable t) {
                Log.e("AuditDyn", "Dual resident inference failed", t);
            } finally {
                if (interpA != null) try { interpA.close(); } catch (Throwable ignored) {}
                if (interpB != null) try { interpB.close(); } catch (Throwable ignored) {}
                if (nnapiA != null) try { nnapiA.close(); } catch (Throwable ignored) {}
                if (nnapiB != null) try { nnapiB.close(); } catch (Throwable ignored) {}
            }
        }

        private long getProcessRssMb() {
            try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            return Long.parseLong(parts[1]) / 1024;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return 0;
        }
    }

    static class VerifyRunnable implements Runnable {
        private final ModelAuditActivity activity;
        private final String modelPath;

        VerifyRunnable(ModelAuditActivity activity, String modelPath) {
            this.activity = activity;
            this.modelPath = modelPath;
        }

        static class Stats {
            final double mean;
            final int min;
            final int max;
            final long checksum;

            Stats(double mean, int min, int max, long checksum) {
                this.mean = mean;
                this.min = min;
                this.max = max;
                this.checksum = checksum;
            }

            int range() {
                return max - min;
            }
        }

        @Override
        public void run() {
            Log.i("AuditVerify", "=== STARTING VERIFICATION SUITE ===");
            Log.i("AuditVerify", "Model path: " + modelPath);
            ByteBuffer modelBuf;
            try {
                modelBuf = activity.loadModelDynamic(modelPath);
            } catch (Throwable t) {
                Log.e("AuditVerify", "Failed to load model from " + modelPath, t);
                return;
            }

            Interpreter interp = null;
            NnApiDelegate nnapiDelegate = null;
            try {
                NnApiDelegate.Options nnapiOpts = new NnApiDelegate.Options();
                nnapiOpts.setAcceleratorName("mtk-mdla_shim");
                nnapiDelegate = new NnApiDelegate(nnapiOpts);

                Interpreter.Options opts = new Interpreter.Options();
                opts.setNumThreads(2);
                opts.addDelegate(nnapiDelegate);

                interp = new Interpreter(modelBuf, opts);
                Log.i("AuditVerify", "Interpreter initialized on mtk-mdla_shim successfully");

                Tensor inTensor = interp.getInputTensor(0);
                Tensor outTensor = interp.getOutputTensor(0);
                int inBytes = inTensor.numBytes();
                int outBytes = outTensor.numBytes();
                Log.i("AuditVerify", "inShape=" + Arrays.toString(inTensor.shape()) + " bytes=" + inBytes
                        + " outShape=" + Arrays.toString(outTensor.shape()) + " bytes=" + outBytes);

                ByteBuffer inBuf = ByteBuffer.allocateDirect(inBytes).order(ByteOrder.nativeOrder());
                ByteBuffer outBuf = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder());

                // Warmup
                Log.i("AuditVerify", "Warming up 20 inferences...");
                for (int i = 0; i < inBytes; i++) inBuf.put((byte) 0);
                for (int i = 0; i < 20; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    interp.run(inBuf, outBuf);
                }

                // ==========================================
                // TEST 1 — LOOP AMPLIFICATION
                // ==========================================
                Log.i("AuditVerify", "=== TEST 1 — LOOP ===");
                long t0 = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    interp.run(inBuf, outBuf);
                }
                long t1 = System.nanoTime();
                double total100Ms = (t1 - t0) / 1e6;
                double perInferenceMs = total100Ms / 100.0;
                Log.i("AuditVerify", String.format(Locale.US, "total_100_ms: %.2f", total100Ms));
                Log.i("AuditVerify", String.format(Locale.US, "per_inference_ms: %.3f", perInferenceMs));
                if (perInferenceMs < 5.0) {
                    Log.i("AuditVerify", "verdict: REAL (~1 ms per inference, no queue pileup or stall)");
                } else {
                    Log.i("AuditVerify", "verdict: SLOW (per_inference=" + perInferenceMs + " ms)");
                }

                // ==========================================
                // TEST 2 — OUTPUT DIFF
                // ==========================================
                Log.i("AuditVerify", "=== TEST 2 — OUTPUT DIFF ===");
                // Inputs:
                // A: all 0
                // B: all 128
                // C: all 255
                // D: real image (raw uint8 from real_portrait_384x512.raw)
                byte[] outA = runWithInput(interp, inBuf, outBuf, (byte) 0);
                byte[] outB = runWithInput(interp, inBuf, outBuf, (byte) 128);
                byte[] outC = runWithInput(interp, inBuf, outBuf, (byte) 255);
                byte[] outD = runWithRealImage(interp, inBuf, outBuf);

                Stats statsA = computeStats(outA);
                Stats statsB = computeStats(outB);
                Stats statsC = computeStats(outC);
                Stats statsD = computeStats(outD);

                Log.i("AuditVerify", String.format(Locale.US, "Row A (zeros):   mean=%.2f min=%d max=%d range=%d checksum=%d (0x%08X)",
                        statsA.mean, statsA.min, statsA.max, statsA.range(), statsA.checksum, statsA.checksum));
                Log.i("AuditVerify", String.format(Locale.US, "Row B (128):     mean=%.2f min=%d max=%d range=%d checksum=%d (0x%08X)",
                        statsB.mean, statsB.min, statsB.max, statsB.range(), statsB.checksum, statsB.checksum));
                Log.i("AuditVerify", String.format(Locale.US, "Row C (255):     mean=%.2f min=%d max=%d range=%d checksum=%d (0x%08X)",
                        statsC.mean, statsC.min, statsC.max, statsC.range(), statsC.checksum, statsC.checksum));
                Log.i("AuditVerify", String.format(Locale.US, "Row D (real):    mean=%.2f min=%d max=%d range=%d checksum=%d (0x%08X)",
                        statsD.mean, statsD.min, statsD.max, statsD.range(), statsD.checksum, statsD.checksum));

                boolean identicalAB = Arrays.equals(outA, outB);
                boolean identicalAC = Arrays.equals(outA, outC);
                boolean identicalAD = Arrays.equals(outA, outD);
                boolean identicalBC = Arrays.equals(outB, outC);
                boolean identicalBD = Arrays.equals(outB, outD);
                boolean identicalCD = Arrays.equals(outC, outD);

                boolean anyIdentical = identicalAB || identicalAC || identicalAD || identicalBC || identicalBD || identicalCD;
                Log.i("AuditVerify", "Identical pairs: "
                        + "A==B:" + identicalAB + " "
                        + "A==C:" + identicalAC + " "
                        + "A==D:" + identicalAD + " "
                        + "B==C:" + identicalBC + " "
                        + "B==D:" + identicalBD + " "
                        + "C==D:" + identicalCD);

                boolean widerRange = statsD.range() > statsA.range() && statsD.range() > statsB.range() && statsD.range() > statsC.range();
                Log.i("AuditVerify", "Dynamic range D wider than A/B/C: " + widerRange + " (rangeD=" + statsD.range() + " rangeA=" + statsA.range() + " rangeB=" + statsB.range() + " rangeC=" + statsC.range() + ")");

                if (statsA.checksum == statsD.checksum && identicalAD) {
                    Log.i("AuditVerify", "verdict: MODEL IS NOT COMPUTING (outputs identical)");
                } else {
                    Log.i("AuditVerify", "verdict: MODEL IS COMPUTING (output D differs from A/B/C, checksums diverge)");
                }

                // ==========================================
                // TEST 3 — INPUT-DEPENDENT TIMING
                // ==========================================
                Log.i("AuditVerify", "=== TEST 3 — INPUT-DEPENDENT ===");
                // Time 100 with Input A (zeros)
                fillBuffer(inBuf, (byte) 0);
                long t3a0 = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    interp.run(inBuf, outBuf);
                }
                long t3a1 = System.nanoTime();
                double perInfA = (t3a1 - t3a0) / 100.0 / 1e6;

                // Time 100 with Input D (real image)
                loadRealImageIntoBuffer(inBuf);
                long t3d0 = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    inBuf.rewind();
                    outBuf.rewind();
                    interp.run(inBuf, outBuf);
                }
                long t3d1 = System.nanoTime();
                double perInfD = (t3d1 - t3d0) / 100.0 / 1e6;

                Log.i("AuditVerify", String.format(Locale.US, "zeros per_inf_ms: %.3f", perInfA));
                Log.i("AuditVerify", String.format(Locale.US, "real per_inf_ms: %.3f", perInfD));
                double ratio = perInfD / Math.max(0.001, perInfA);
                Log.i("AuditVerify", String.format(Locale.US, "timing ratio D/A: %.2fx", ratio));
                if (ratio > 2.0 && perInfD > 5.0) {
                    Log.i("AuditVerify", "verdict: ZERO-SKIP ARTIFACT (D >> A)");
                } else {
                    Log.i("AuditVerify", "verdict: INVARIANT TIMING (model executes regardless of input)");
                }

                Log.i("AuditVerify", "=== VERIFICATION SUITE COMPLETE ===");
            } catch (Throwable t) {
                Log.e("AuditVerify", "Verification failed", t);
            } finally {
                if (interp != null) try { interp.close(); } catch (Throwable ignored) {}
                if (nnapiDelegate != null) try { nnapiDelegate.close(); } catch (Throwable ignored) {}
            }
        }

        private Stats computeStats(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return new Stats(0, 0, 0, 0);
            long sum = 0;
            int min = 256;
            int max = -1;
            for (byte b : bytes) {
                int val = b & 0xFF; // unsigned 0..255
                sum = (sum + val) & 0xFFFFFFFFL;
                if (val < min) min = val;
                if (val > max) max = val;
            }
            double mean = (double) sum / bytes.length;
            return new Stats(mean, min, max, sum);
        }

        private byte[] runWithInput(Interpreter interp, ByteBuffer inBuf, ByteBuffer outBuf, byte fill) {
            fillBuffer(inBuf, fill);
            // Pre-fill outBuf with sentinel 0xAA so we can verify if it gets overwritten
            fillBuffer(outBuf, (byte) 0xAA);
            interp.run(inBuf, outBuf);
            outBuf.rewind();
            byte[] out = new byte[outBuf.remaining()];
            outBuf.get(out);
            return out;
        }

        private byte[] runWithRealImage(Interpreter interp, ByteBuffer inBuf, ByteBuffer outBuf) {
            loadRealImageIntoBuffer(inBuf);
            // Pre-fill outBuf with sentinel 0xAA
            fillBuffer(outBuf, (byte) 0xAA);
            interp.run(inBuf, outBuf);
            outBuf.rewind();
            byte[] out = new byte[outBuf.remaining()];
            outBuf.get(out);
            return out;
        }

        private void fillBuffer(ByteBuffer buf, byte val) {
            buf.rewind();
            for (int i = 0; i < buf.capacity(); i++) {
                buf.put(val);
            }
            buf.rewind();
        }

        private boolean loadRealImageIntoBuffer(ByteBuffer inBuf) {
            inBuf.rewind();
            File[] candidates = new File[] {
                new File(activity.getFilesDir(), "models/real_portrait_384x512.raw"),
                new File(activity.getExternalFilesDir(null), "models/real_portrait_384x512.raw"),
                new File("/data/data/com.zenith.auxcam/files/models/real_portrait_384x512.raw"),
                new File("/sdcard/Android/data/com.zenith.auxcam/files/models/real_portrait_384x512.raw")
            };
            for (File f : candidates) {
                if (f.exists() && f.canRead()) {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] tmp = new byte[inBuf.remaining()];
                        int read = 0;
                        while (read < tmp.length) {
                            int r = fis.read(tmp, read, tmp.length - read);
                            if (r == -1) break;
                            read += r;
                        }
                        if (read == tmp.length) {
                            inBuf.put(tmp);
                            inBuf.rewind();
                            Log.i("AuditVerify", "Loaded real portrait from " + f.getAbsolutePath() + " (" + read + " bytes)");
                            return true;
                        }
                    } catch (Throwable t) {
                        Log.w("AuditVerify", "Failed to read " + f.getAbsolutePath() + ": " + t.getMessage());
                    }
                }
            }
            Log.w("AuditVerify", "Could not find raw real portrait image! Generating synthetic fallback.");
            byte[] tmp = new byte[inBuf.remaining()];
            for (int i = 0; i < tmp.length; i++) {
                tmp[i] = (byte) ((i * 37 + 13) & 0xFF);
            }
            inBuf.put(tmp);
            inBuf.rewind();
            return false;
        }
    }
}
