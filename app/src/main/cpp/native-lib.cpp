#include <jni.h>
#include <string>
#include "DspFaust.h"

static DspFaust* gDspFaust = nullptr;//pointer to the faust audio engine, kept here so it persists across JNI calls and UI events

extern "C" {

JNIEXPORT void JNICALL//initialising the Faust engine and using the best sample rate and buffer size for low latency
Java_com_example_voicechanger_AudioEngine_nativeStart(JNIEnv *env, jobject thiz, jint sampleRate, jint bufferSize) {
    if (!gDspFaust) {//stops double initialisation
        gDspFaust = new DspFaust(sampleRate, bufferSize);
        gDspFaust->start();
    }
}

JNIEXPORT void JNICALL//stops the audio processing and frees the memory
Java_com_example_voicechanger_AudioEngine_nativeStop(JNIEnv *env, jobject thiz) {
    if (gDspFaust) {
        gDspFaust->stop();
        delete gDspFaust;
        gDspFaust = nullptr;
    }
}

JNIEXPORT void JNICALL//pushes changes of the parameters
Java_com_example_voicechanger_AudioEngine_nativeSetParam(JNIEnv *env, jobject thiz, jstring path, jfloat value) {
    if (gDspFaust) {
        const char *nativePath = env->GetStringUTFChars(path, 0);//the JVM string needs converted to a C string to be read
        gDspFaust->setParamValue(nativePath, value);
        env->ReleaseStringUTFChars(path, nativePath);//drop the string reference to avoid leaks every change
    }
}

JNIEXPORT jfloat JNICALL//gets the current value of a a parameter
Java_com_example_voicechanger_AudioEngine_nativeGetParam(JNIEnv *env, jobject thiz, jstring path) {
    if (gDspFaust) {
        const char *nativePath = env->GetStringUTFChars(path, 0);
        float value = gDspFaust->getParamValue(nativePath);
        env->ReleaseStringUTFChars(path, nativePath);
        return value;
    }
    return 0.0f;
}

}