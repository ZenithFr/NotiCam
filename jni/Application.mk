APP_ABI := arm64-v8a
APP_PLATFORM := android-35
APP_STL := c++_shared

# NDK r27 opt-in: build our engine for both 4 KB and 16 KB page devices.
# This does not repair third-party prebuilt libraries; audit their ELF separately.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
