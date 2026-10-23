LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := speex_echo
LOCAL_SRC_FILES := speex_echo_jni.c \
    speexdsp/mdf.c speexdsp/preprocess.c speexdsp/fftwrap.c \
    speexdsp/kiss_fft.c speexdsp/kiss_fftr.c speexdsp/filterbank.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/speexdsp $(LOCAL_PATH)/speexdsp/include
LOCAL_CFLAGS := -O2 -DFLOATING_POINT -DUSE_KISS_FFT -DEXPORT= -Wno-unused-parameter
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

# Root USB gadget helper for the pre-iOS-16 wired CarPlay path. This is a PIE executable;
# Gradle renames it to libdiplay-gadget.so into a generated jniLibs dir so the package manager
# extracts it into nativeLibraryDir, the only place Android 10+ lets an app exec from.
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_gadget
LOCAL_SRC_FILES := legacy_gadget/main.c
LOCAL_CFLAGS := -std=gnu11 -Wall -Wextra -O2
include $(BUILD_EXECUTABLE)
