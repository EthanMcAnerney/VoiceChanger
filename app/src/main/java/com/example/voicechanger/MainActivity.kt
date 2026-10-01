package com.example.voicechanger

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicechanger.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val audioEngine = AudioGlobals.engine//shared single instance, allows the UI and background services to control the same session
    private lateinit var presetManager: PresetManager

    private val currentValues = mutableMapOf<String, Float>().apply {//cache of current dsp values for the UI
        putAll(PresetManager.cleanDefaults)
    }

    private val seekBarMap = mutableMapOf<String, SeekBar>()//ui references
    private val valueTextMap = mutableMapOf<String, TextView>()
    private val checkBoxMap = mutableMapOf<String, CheckBox>()
    private var isUpdatingUIFromPreset = false//guard flag, stops infinite updates when changing sliders
    private var isAudioPaused = false
    private var savedVolumeBeforeMute = 1.0f
    private var latestPresetNames = listOf<String>()

    //defining the dynamic Ui layout
    data class ParamMeta(
        val path: String, val label: String, val description: String, val minLabel: String, val maxLabel: String, val min: Float, val max: Float, val step: Float = 0.1f, val isCheckbox: Boolean = false
    )

    data class ParamCategory(val title: String, val defaultOpen: Boolean, val params: List<ParamMeta>)

    private val categories = listOf(//have to match the Faust declaration names
        ParamCategory("Output", true, listOf(
            ParamMeta("9_Master_Bypass", "Bypass Effects", "Turns off all processing", "Active", "Bypassed", 0f, 1f, 1f, isCheckbox = true),
            ParamMeta("9_Master_Volume", "Main Volume", "Overall output volume level.", "Mute", "Loud (5x)", 0f, 5f, 0.01f)
        )),
        ParamCategory("Core Changes", true, listOf(
            ParamMeta("2_Pitch_Semitones", "Pitch Shift", "Core voice change.", "Demon", "Chipmunk", -24f, 24f, 0.1f),
            ParamMeta("3_Throat_Size", "Throat Size", "Alters vocal tract length. Combine with pitch for greater effect.", "Massive Giant", "Tiny Mouse", -12f, 12f, 0.1f),
            ParamMeta("3_Chest_Warmth", "Sub Harmonic Body", "Adds or removes deep chest resonance.", "Thin", "Booming", -15f, 15f, 0.1f),
            ParamMeta("2_SubOctave_Mix", "Sub Octave", "Blends a deep octave underneath your voice.", "Off", "Loud Monster", 0f, 1f, 0.01f),
            ParamMeta("2_HighOctave_Mix", "High Octave", "Blends a high octave above your voice.", "Off", "Loud Shimmer", 0f, 1f, 0.01f)
        )),
        ParamCategory("Sci-Fi", true, listOf(
            ParamMeta("6_Mod_RobotMix", "Robot Synth Blend", "Blend between a human or robotic voice.", "Human", "Full Robot", 0f, 1f, 0.01f),
            ParamMeta("6_Mod_RobotFreq", "Robot Tone Pitch", "The frequency of the robot modulation.", "Low Buzz", "High Shimmer", 0f, 1000f, 1f),
            ParamMeta("6_Mod_ChorusDepth", "Alien Swarm (Cloning)", "Creates a alien doubling effect.", "Single Voice", "Swarm", 0f, 1f, 0.01f),
            ParamMeta("6_Mod_ChorusRate", "Alien Swarm Speed", "How fast the clones speak around you.", "Slow", "Fast", 0.1f, 10f, 0.1f),
            ParamMeta("2_Tremolo_Depth", "Stutter Chopper Depth", "Chops your volume on and off, like speaking through a fan.", "Smooth", "Hard Chop", 0f, 1f, 0.01f),
            ParamMeta("2_Tremolo_Rate_Hz", "Stutter Speed", "How fast the chop pulses.", "Slow Pulse", "Fast Pulse", 0f, 20f, 0.1f),
            ParamMeta("2_Vibrato_Depth", "Pitch Wobble", "Bends your pitch up and down. (Ghost effect)", "Steady", "Wobble", 0f, 1f, 0.01f),
            ParamMeta("2_Vibrato_Rate_Hz", "Pitch Wobble Speed", "How fast the pitch bends.", "Slow", "Fast", 0.1f, 15f, 0.1f)
        )),
        ParamCategory("5-Band EQ", false, listOf(
            ParamMeta("4_EQ_1_Sub", "60 Hz (Sub-Bass)", "Deep rumble.", "-15 dB", "+15 dB", -15f, 15f, 0.1f),
            ParamMeta("4_EQ_2_Low", "250 Hz (Low-Mid)", "Vocal warmth and mud.", "-15 dB", "+15 dB", -15f, 15f, 0.1f),
            ParamMeta("4_EQ_3_Mid", "1 kHz (Mid)", "Core speech frequencies.", "-15 dB", "+15 dB", -15f, 15f, 0.1f),
            ParamMeta("4_EQ_4_High", "4 kHz (High-Mid)", "Boost this to make words easier to understand.", "-15 dB", "+15 dB", -15f, 15f, 0.1f),
            ParamMeta("4_EQ_5_Presence", "12 kHz (Treble)", "Air and crispness.", "-15 dB", "+15 dB", -15f, 15f, 0.1f)
        )),
        ParamCategory("Echo & Space", false, listOf(
            ParamMeta("8_Space_DelayTime", "Echo Time Gap", "Time between each echo reflection.", "Instant Slap", "Long Canyon", 0.001f, 2f, 0.01f),
            ParamMeta("8_Space_DelayFeedback", "Echo Repeat Count", "How many times your echoes repeat.", "One Echo", "Infinite", 0f, 0.9f, 0.01f),
            ParamMeta("8_Space_DelayMix", "Echo Volume", "How loud the echoes are.", "Dry", "Loud Echoes", 0f, 1f, 0.01f),
            ParamMeta("8_Space_ReverseMix", "DJ Backspin", "Reverse words spoken to sound like a DJ backspin.", "Off/Quiet", "Loud", 0f, 1f, 0.01f),
            ParamMeta("8_Space_ReverbMix", "Virtual Room Size", "Simulates physical space.", "Dry Booth", "Massive Hall", 0f, 1f, 0.01f)
        )),
        ParamCategory("Filters", false, listOf(
            ParamMeta("5_Dist_Decimate", "Toy Walkie-Talkie", "Lowers the audio sample rate for a cheap plastic speaker effect.", "Unaltered", "Toy Mic", 0f, 1f, 0.01f),
            ParamMeta("5_Dist_Bitcrush", "Audio Resolution", "Changes resolution.", "Crunchy", "Unaltered", 1f, 16f, 0.1f),
            ParamMeta("7_Comb_Resonance", "Drone", "Creates a ringing, metallic tube resonance.", "Soft", "Harsh", 0f, 1f, 0.01f),
            ParamMeta("7_Filter_HighPass", "High-Pass Filter", "Cuts bass.", "Off (20 Hz)", "Phone (2000 Hz)", 20f, 2000f, 1f),
            ParamMeta("7_Filter_LowPass", "Low-Pass Filter", "Cuts treble.", "Muffled (500 Hz)", "Off (20000 Hz)", 500f, 20000f, 1f)
        )),
        ParamCategory("Studio Cleanup", false, listOf(
            ParamMeta("1_Gate_Threshold_dB", "Volume Threshold (Gate)", "Only processes noise above a desired level, adjust to cut out background noise.", "Off / Open", "Aggressive Gate", -90f, 0f, 1f),
            ParamMeta("1_Comp_Threshold_dB", "Volume Leveler Level", "Smooths out volume so shouts and whispers match closer.", "Full Dynamic", "Squashed", -60f, 0f, 1f),
            ParamMeta("1_Comp_Ratio", "Volume Squeeze Punch", "How aggressively the leveler squeezes your audio peaks.", "Subtle", "Radio Punch", 1f, 20f, 0.1f),
            ParamMeta("1_Comp_Attack_ms", "Leveler Reaction Speed", "How fast the leveler reacts when you start talking.", "Instant", "Slower Punch", 1f, 100f, 1f),
            ParamMeta("1_Comp_Release_ms", "Leveler Recovery Speed", "How fast the leveler lets go after you stop speaking.", "Quick Fade", "Long Sustain", 10f, 1000f, 1f)
        ))
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startEngine()
        else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        presetManager = PresetManager(this)

        buildDynamicUI()
        setupControls()
        observePresets()

        if (audioEngine.hasMicrophonePermission(this)) {
            startEngine()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                //force the default dsp values on boot to make sure noise thresholds are active on start
                for ((path, value) in PresetManager.cleanDefaults) {
                    audioEngine.setParam(path, value)
                }
            }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    //builds the dynamic ui based off the ParamMeta definitions, stops xml bloat and allows for the easier addition of new effects
    private fun buildDynamicUI() {
        val container = binding.parametersContainer
        container.removeAllViews()

        for (cat in categories) {//iterate through each category
            val categoryLayout = LinearLayout(this).apply {//building the outer section
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 16)
            }

            val headerView = TextView(this).apply {//building the collapsable headers
                text = if (cat.defaultOpen) "▼  ${cat.title}" else "▶  ${cat.title}"
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(24, 20, 24, 20)
                setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
                setTextColor(resources.getColor(android.R.color.white, theme))
            }

            val contentLayout = LinearLayout(this).apply {//the container holding the sliders for the current category
                orientation = LinearLayout.VERTICAL
                visibility = if (cat.defaultOpen) View.VISIBLE else View.GONE
                setPadding(8, 12, 8, 4)
            }

            headerView.setOnClickListener {
                if (contentLayout.visibility == View.VISIBLE) {
                    contentLayout.visibility = View.GONE
                    headerView.text = "▶  ${cat.title}"
                } else {
                    contentLayout.visibility = View.VISIBLE
                    headerView.text = "▼  ${cat.title}"
                }
            }

            for (meta in cat.params) {//iterate through each parameter in the category
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 8, 8, 20)
                }

                if (meta.isCheckbox) {//boolean parameter (only bypass) should be checkboxes
                    val checkBox = CheckBox(this).apply {
                        text = meta.label
                        isChecked = (currentValues[meta.path] == 1.0f)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (!isUpdatingUIFromPreset) {
                                val valFloat = if (isChecked) 1.0f else 0.0f
                                currentValues[meta.path] = valFloat
                                audioEngine.setParam(meta.path, valFloat)
                            }
                        }
                    }
                    checkBoxMap[meta.path] = checkBox
                    itemLayout.addView(checkBox)
                } else {//other parameters render as sliders
                    val titleRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val titleView = TextView(this).apply {
                        text = meta.label
                        textSize = 15f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val singleResetBtn = Button(this).apply {//a reset button to the specific parameter
                        text = "↺"
                        textSize = 11f
                        setPadding(12, 0, 12, 0)
                        minimumWidth = 0
                        minWidth = 0
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 8 }
                        setOnClickListener {
                            val defaultVal = PresetManager.cleanDefaults[meta.path] ?: meta.min
                            currentValues[meta.path] = defaultVal
                            audioEngine.setParam(meta.path, defaultVal)

                            val valueView = valueTextMap[meta.path]
                            val seekBar = seekBarMap[meta.path]
                            val range = meta.max - meta.min

                            valueView?.text = "$defaultVal"
                            seekBar?.progress = (((defaultVal - meta.min) / range) * 1000).toInt()//make the float to a 1-1000int for the seekbar
                        }
                    }

                    val valueView = TextView(this).apply {//the value number can be clicked to allow manual entry
                        text = "${currentValues[meta.path] ?: meta.min}"
                        textSize = 14f
                        setPadding(6, 2, 6, 2)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(resources.getColor(android.R.color.holo_blue_dark, theme))
                        setOnClickListener {
                            showManualInputDialog(meta, this, seekBarMap[meta.path])
                        }
                    }
                    valueTextMap[meta.path] = valueView

                    titleRow.addView(titleView)
                    titleRow.addView(singleResetBtn)
                    titleRow.addView(valueView)
                    itemLayout.addView(titleRow)

                    val descView = TextView(this).apply {
                        text = meta.description
                        textSize = 11f
                        setTextColor(resources.getColor(android.R.color.darker_gray, theme))
                        setPadding(0, 2, 0, 2)
                    }
                    itemLayout.addView(descView)

                    val endpointsLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, 2)
                    }
                    val minLabelView = TextView(this).apply {
                        text = "◀ ${meta.minLabel}"
                        textSize = 9f
                        setTextColor(resources.getColor(android.R.color.holo_blue_dark, theme))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val maxLabelView = TextView(this).apply {
                        text = "${meta.maxLabel} ▶"
                        textSize = 9f
                        gravity = Gravity.END
                        setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    endpointsLayout.addView(minLabelView)
                    endpointsLayout.addView(maxLabelView)
                    itemLayout.addView(endpointsLayout)

                    val seekBar = SeekBar(this).apply {//the control
                        val range = meta.max - meta.min
                        //seekbars only support integers, 1000 is used and scaled down later
                        max = 1000
                        val initialVal = currentValues[meta.path] ?: meta.min
                        progress = (((initialVal - meta.min) / range) * 1000).toInt()

                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser && !isUpdatingUIFromPreset) {
                                    val computedVal = meta.min + (progress.toFloat() / 1000f) * range
                                    val multiplier = 1f / meta.step
                                    val snappedVal = Math.round(computedVal * multiplier) / multiplier
                                    currentValues[meta.path] = snappedVal
                                    valueView.text = "$snappedVal"
                                    audioEngine.setParam(meta.path, snappedVal)
                                }
                            }
                            override fun onStartTrackingTouch(sb: SeekBar?) {}
                            override fun onStopTrackingTouch(sb: SeekBar?) {}
                        })
                    }
                    seekBarMap[meta.path] = seekBar
                    itemLayout.addView(seekBar)
                }
                contentLayout.addView(itemLayout)
            }
            categoryLayout.addView(headerView)
            categoryLayout.addView(contentLayout)
            container.addView(categoryLayout)
        }
    }

    private fun showManualInputDialog(meta: ParamMeta, valueView: TextView, seekBar: SeekBar?) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("${currentValues[meta.path] ?: meta.min}")
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Set ${meta.label}")
            .setMessage("Valid Range: ${meta.min} to ${meta.max}")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val textVal = input.text.toString().trim()
                val parsedVal = textVal.toFloatOrNull()

                if (parsedVal != null) {
                    val clampedVal = parsedVal.coerceIn(meta.min, meta.max)
                    val multiplier = 1f / meta.step
                    val snappedVal = Math.round(clampedVal * multiplier) / multiplier

                    currentValues[meta.path] = snappedVal
                    valueView.text = "$snappedVal"

                    val range = meta.max - meta.min
                    seekBar?.progress = (((snappedVal - meta.min) / range) * 1000).toInt()
                    audioEngine.setParam(meta.path, snappedVal)

                    if (parsedVal != clampedVal) {
                        Toast.makeText(this, "Value clamped to range (${meta.min} - ${meta.max})", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Invalid number format entered.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupControls() {
        //mutes by dropping output volume to 0, instead of stopping the service, prevents crashes
        binding.btnPauseAudio.setOnClickListener {
            if (isAudioPaused) {
                audioEngine.setParam("9_Master_Volume", savedVolumeBeforeMute)
                currentValues["9_Master_Volume"] = savedVolumeBeforeMute

                // Update UI volume slider to match restored volume
                val volumeMeta = categories[0].params.getOrNull(1)
                if (volumeMeta != null) {
                    val range = volumeMeta.max - volumeMeta.min
                    seekBarMap["9_Master_Volume"]?.progress = (((savedVolumeBeforeMute - volumeMeta.min) / range) * 1000).toInt()
                    valueTextMap["9_Master_Volume"]?.text = "$savedVolumeBeforeMute"
                }

                isAudioPaused = false
                binding.btnPauseAudio.text = "Pause Audio (Mute)"
                binding.btnPauseAudio.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark)
                Toast.makeText(this, "Audio Unmuted", Toast.LENGTH_SHORT).show()
            } else {
                savedVolumeBeforeMute = currentValues["9_Master_Volume"] ?: 1.0f
                if (savedVolumeBeforeMute <= 0f) savedVolumeBeforeMute = 1.0f

                audioEngine.setParam("9_Master_Volume", 0.0f)
                currentValues["9_Master_Volume"] = 0.0f

                seekBarMap["9_Master_Volume"]?.progress = 0
                valueTextMap["9_Master_Volume"]?.text = "0.0"

                isAudioPaused = true
                binding.btnPauseAudio.text = "Resume Audio (Muted)"
                binding.btnPauseAudio.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
                Toast.makeText(this, "Audio Muted / Paused", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDeletePreset.setOnClickListener {
            val selected = binding.presetSpinner.selectedItem?.toString() ?: "Default (Clean)"
            if (selected == "Default (Clean)") {
                Toast.makeText(this, "Cannot delete Default (Clean)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Delete Preset")
                .setMessage("Are you sure you want to delete preset '$selected'?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        presetManager.deletePreset(selected)
                        Toast.makeText(this@MainActivity, "Preset '$selected' deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnEditPresets.setOnClickListener {
            if (latestPresetNames.isEmpty()) {
                Toast.makeText(this, "No custom presets available to edit.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showEditPresetsDialog()
        }

        binding.btnReset.setOnClickListener {
            lifecycleScope.launch {
                isUpdatingUIFromPreset = true
                presetManager.resetToDefaults(audioEngine)
                currentValues.putAll(PresetManager.cleanDefaults)

                for (cat in categories) {
                    for (meta in cat.params) {
                        val defaultVal = PresetManager.cleanDefaults[meta.path] ?: meta.min
                        if (meta.isCheckbox) {
                            checkBoxMap[meta.path]?.isChecked = (defaultVal == 1.0f)
                        } else {
                            val seekBar = seekBarMap[meta.path]
                            val valText = valueTextMap[meta.path]
                            val range = meta.max - meta.min
                            seekBar?.progress = (((defaultVal - meta.min) / range) * 1000).toInt()
                            valText?.text = "$defaultVal"
                        }
                    }
                }
                isUpdatingUIFromPreset = false
                isAudioPaused = false
                binding.btnPauseAudio.text = "Pause Audio (Mute)"
                binding.btnPauseAudio.backgroundTintList = getColorStateList(android.R.color.holo_orange_dark)
                Toast.makeText(this@MainActivity, "Reset all to clean defaults", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSavePreset.setOnClickListener {
            showSavePresetDialog()
        }
    }

    private fun showEditPresetsDialog() {
        val items = latestPresetNames.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Preset to Edit")
            .setItems(items) { _, which ->
                val chosenPreset = items[which]
                showPresetOptionsDialog(chosenPreset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresetOptionsDialog(presetName: String) {
        val options = arrayOf("Rename", "Move Up", "Move Down")
        AlertDialog.Builder(this)
            .setTitle("Edit: $presetName")
            .setItems(options) { _, optionWhich ->
                when (optionWhich) {
                    0 -> showRenameDialog(presetName)
                    1 -> movePreset(presetName, -1)
                    2 -> movePreset(presetName, 1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Preset")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) {
                    lifecycleScope.launch {
                        presetManager.renamePreset(oldName, newName)
                        Toast.makeText(this@MainActivity, "Renamed to '$newName'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun movePreset(presetName: String, direction: Int) {
        val mutableList = latestPresetNames.toMutableList()
        val index = mutableList.indexOf(presetName)
        val targetIndex = index + direction
        if (targetIndex in mutableList.indices) {
            mutableList.removeAt(index)
            mutableList.add(targetIndex, presetName)
            lifecycleScope.launch {
                presetManager.reorderPresets(mutableList)
                Toast.makeText(this@MainActivity, "Preset reordered", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pendingSelectedPresetName: String? = null

    private fun showSavePresetDialog() {
        val input = EditText(this).apply { hint = "Preset Name (e.g., Deep Monster)" }
        AlertDialog.Builder(this)
            .setTitle("Save Custom Preset")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        // 1. Tell the app to auto-select this name once the preset list updates
                        pendingSelectedPresetName = name

                        // 2. Save to disk
                        presetManager.saveCustomPreset(name, currentValues)
                        Toast.makeText(this@MainActivity, "Preset '$name' saved successfully!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var lastSelectedPresetIndex = 0

    private fun observePresets() {
        binding.presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUIFromPreset) return
                if (position == lastSelectedPresetIndex) return

                lastSelectedPresetIndex = position
                val presetList = mutableListOf("Default (Clean)") + latestPresetNames
                val selectedPreset = presetList.getOrNull(position) ?: "Default (Clean)"

                //preset loads on a background thread
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    isUpdatingUIFromPreset = true
                    if (selectedPreset == "Default (Clean)") {
                        presetManager.resetToDefaults(audioEngine)
                        currentValues.putAll(PresetManager.cleanDefaults)
                    } else {
                        val loaded = presetManager.loadPreset(selectedPreset, audioEngine)
                        currentValues.putAll(loaded)
                    }

                    //update on main thread
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        for (cat in categories) {
                            for (meta in cat.params) {
                                val value = currentValues[meta.path] ?: meta.min
                                if (meta.isCheckbox) {
                                    checkBoxMap[meta.path]?.isChecked = (value == 1.0f)
                                } else {
                                    val seekBar = seekBarMap[meta.path]
                                    val valText = valueTextMap[meta.path]
                                    val range = meta.max - meta.min
                                    seekBar?.progress = (((value - meta.min) / range) * 1000).toInt()
                                    valText?.text = "$value"
                                }
                            }
                        }
                        isUpdatingUIFromPreset = false
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        //get preset names from disk and update
        lifecycleScope.launch {
            presetManager.savedPresetNames.collect { savedNames ->
                latestPresetNames = savedNames
                val presetList = mutableListOf("Default (Clean)") + savedNames
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, presetList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

                isUpdatingUIFromPreset = true
                binding.presetSpinner.adapter = adapter

                //check for a new preset
                val targetIndex = if (pendingSelectedPresetName != null) {
                    val idx = presetList.indexOf(pendingSelectedPresetName)
                    pendingSelectedPresetName = null
                    if (idx >= 0) idx else lastSelectedPresetIndex
                } else {
                    lastSelectedPresetIndex
                }

                if (targetIndex < presetList.size) {
                    lastSelectedPresetIndex = targetIndex
                    binding.presetSpinner.setSelection(targetIndex, false)
                }
                isUpdatingUIFromPreset = false
            }
        }
    }

    //audio intialisation to a foreground service so it can work with the app or phoen closed
    private fun startEngine() {
        try {
            if (!isAudioPaused) {
                val serviceIntent = Intent(this, VoiceChangerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Audio Engine Running in Background", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Audio Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The audio engine is not stopped here, lifecycle management happens in VoiceChangerService
    }
}