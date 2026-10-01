package com.example.voicechanger

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "voice_presets")//jetpack datastore for local storage

class PresetManager(private val context: Context) {

    companion object {
        private val PRESET_NAMES_KEY = stringPreferencesKey("saved_preset_names_csv")//the list of custom presets is saved as a CSV, allows for user customisation

        //All Faust DSP parameters (with some old removed ones)
        val cleanDefaults = mapOf(
            //DYNAMICS
            "1_Gate_Threshold_dB" to -50f,//set to -50 by default to stop background noise by default
            "1_Comp_Threshold_dB" to -60f,
            "1_Comp_Ratio" to 1f,
            "1_Comp_Attack_ms" to 10f,
            "1_Comp_Release_ms" to 10f,

            //CORE VOICE CHANGES
            "2_Pitch_Semitones" to 0f,
            "2_AutoTune_Snap" to 0f, //no longer active
            "2_SubOctave_Mix" to 0f,
            "2_HighOctave_Mix" to 0f,
            "2_Harmony_Semitones" to 0f,
            "2_Harmony_Mix" to 0f,
            "2_Tremolo_Rate_Hz" to 0f,
            "2_Tremolo_Depth" to 0f,
            "2_Vibrato_Depth" to 0f,
            "2_Vibrato_Rate_Hz" to 1f,

            //THROAT AND BODY
            "3_Throat_Size" to 0f,
            "3_Chest_Warmth" to 0f,
            "3_Air_Breathiness" to 0f,
            "3_Vowel_Morph" to 0f,

            //EQ
            "4_EQ_1_Sub" to 0f,
            "4_EQ_2_Low" to 0f,
            "4_EQ_3_Mid" to 0f,
            "4_EQ_4_High" to 0f,
            "4_EQ_5_Presence" to 0f,

            //DISTORTION
            "5_Dist_Fuzz" to 0f,
            "5_Dist_Bitcrush" to 16f,
            "5_Dist_Decimate" to 0f,

            //SCIFI
            "6_Mod_RobotFreq" to 0f,
            "6_Mod_RobotMix" to 0f,
            "6_Mod_ChorusDepth" to 0f,
            "6_Mod_ChorusRate" to 1f,
            "6_Mod_PhaserDepth" to 0f,
            "6_Mod_PhaserRate" to 1f,

            //FILTERS
            "7_Comb_Resonance" to 0f,
            "7_Filter_HighPass" to 20f,
            "7_Filter_LowPass" to 20000f,
            "7_Filter_WahFreq" to 500f,
            "7_Filter_WahMix" to 0f,

            //SPACE AND ECHO
            "8_Space_DelayTime" to 0f,
            "8_Space_DelayFeedback" to 0f,
            "8_Space_DelayMix" to 0f,
            "8_Space_ReverseMix" to 0f,
            "8_Space_ReverbDamp" to 0f,
            "8_Space_ReverbMix" to 0f,

            //MASTER OUTPUT
            "9_Master_Pan" to 0f,
            "9_Master_Volume" to 1.5f,
            "9_Master_Bypass" to 0f
        )
    }

    //saved preset names as an ordered list preserving user order
    val savedPresetNames: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val csv = prefs[PRESET_NAMES_KEY] ?: ""
        if (csv.isBlank()) emptyList() else csv.split(",")
    }

    suspend fun saveCustomPreset(presetName: String, currentValues: Map<String, Float>) {
        context.dataStore.edit { prefs ->
            val csv = prefs[PRESET_NAMES_KEY] ?: ""
            val list = if (csv.isBlank()) mutableListOf() else csv.split(",").toMutableList()
            if (!list.contains(presetName)) {
                list.add(presetName)
                prefs[PRESET_NAMES_KEY] = list.joinToString(",")
            }

            for ((path, value) in currentValues) {
                prefs[floatPreferencesKey("preset_${presetName}_$path")] = value
            }
        }
    }

    suspend fun deletePreset(presetName: String) {
        context.dataStore.edit { prefs ->
            val csv = prefs[PRESET_NAMES_KEY] ?: ""
            val list = if (csv.isBlank()) mutableListOf() else csv.split(",").toMutableList()
            list.remove(presetName)
            prefs[PRESET_NAMES_KEY] = list.joinToString(",")

            for (path in cleanDefaults.keys) {
                prefs.remove(floatPreferencesKey("preset_${presetName}_$path"))
            }
        }
    }

    suspend fun renamePreset(oldName: String, newName: String) {
        context.dataStore.edit { prefs ->
            val csv = prefs[PRESET_NAMES_KEY] ?: ""
            val list = if (csv.isBlank()) mutableListOf() else csv.split(",").toMutableList()
            val index = list.indexOf(oldName)
            if (index != -1 && !list.contains(newName)) {
                list[index] = newName
                prefs[PRESET_NAMES_KEY] = list.joinToString(",")

                for (path in cleanDefaults.keys) {
                    val oldKey = floatPreferencesKey("preset_${oldName}_$path")
                    val newKey = floatPreferencesKey("preset_${newName}_$path")
                    val value = prefs[oldKey]
                    if (value != null) {
                        prefs[newKey] = value
                        prefs.remove(oldKey)
                    }
                }
            }
        }
    }

    suspend fun reorderPresets(newOrder: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[PRESET_NAMES_KEY] = newOrder.joinToString(",")
        }
    }

    //loads a saved preset to the dsp engine
    suspend fun loadPreset(presetName: String, audioEngine: AudioEngine): Map<String, Float> {
        val prefs = context.dataStore.data.first()
        val loadedValues = mutableMapOf<String, Float>()

        for ((path, defaultVal) in cleanDefaults) {
            val key = floatPreferencesKey("preset_${presetName}_$path")
            val value = prefs[key] ?: defaultVal
            loadedValues[path] = value
            audioEngine.setParam(path, value)
        }
        return loadedValues
    }

    suspend fun resetToDefaults(audioEngine: AudioEngine) {
        for ((path, value) in cleanDefaults) {
            audioEngine.setParam(path, value)
        }
    }
}