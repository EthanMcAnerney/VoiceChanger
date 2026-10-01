package com.example.voicechanger

object AudioGlobals {
    //singleton of AudioEngine
    //makes sure that MainActivity and VoiceChangerService interact with the same audio session
    val engine = AudioEngine()
}