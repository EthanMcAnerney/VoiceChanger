package com.example.voicechanger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat

class AudioEngine {
    companion object {
        init {
            System.loadLibrary("voicechanger")//loading the libvoicechanger.so library
        }
    }

    //functions that are linked to the C++ JNI methods in native-lib
    external fun nativeStart(sampleRate: Int, bufferSize: Int)
    external fun nativeStop()
    external fun nativeSetParam(path: String, value: Float)
    external fun nativeGetParam(path: String): Float

    //checks for microphone access
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    //gets the native sample rate and buffer size of the device, used for low latency
    fun getSystemAudioConfig(context: Context): Pair<Int, Int> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000
        val bufferSize = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() ?: 256
        return Pair(sampleRate, bufferSize)
    }

    //Starts the audio pipeline using the hardware configuration
    fun startAudio(context: Context) {
        val (sampleRate, bufferSize) = getSystemAudioConfig(context)
        nativeStart(sampleRate, bufferSize)
    }

    //stops the pipeline
    fun stopAudio() {
        nativeStop()
    }

    //sets a parameter, like that from a slider
    fun setParam(path: String, value: Float) {
        nativeSetParam(path, value)
    }

    //gets the current parameter value from the engine
    fun getParam(path: String): Float {
        return nativeGetParam(path)
    }
}