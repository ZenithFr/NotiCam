# Shizuku talks to the user service over AIDL/Binder; keep the stubs.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface com.zenith.auxcam.IAuxCamService { *; }
-keep interface com.zenith.auxcam.IAuxCamCallback { *; }
-keep class com.zenith.auxcam.CameraUserService { *; }

# JNI entry points on the activity.
-keepclasseswithmembernames class com.zenith.auxcam.MainActivity {
    native <methods>;
}
