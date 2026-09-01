#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_tongsr_kaleido_matrix_nativeprobe_MainActivity_nativeAnswer(
        JNIEnv*, jclass) {
    return 42;
}
