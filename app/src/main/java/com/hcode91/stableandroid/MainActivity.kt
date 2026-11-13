package com.hcode91.stableandroid

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    private data class Choice(val label: String, val value: Int) {
        override fun toString() = label
    }

    private val samplers = listOf(
        Choice("Euler a", 1), Choice("Euler", 0), Choice("DPM++ 2M", 5),
        Choice("DPM++ 2M v2", 6), Choice("DDIM", 10), Choice("LCM", 9),
    )
    private val schedulers = listOf(
        Choice("Default", -1), Choice("Discrete", 0), Choice("Karras", 1),
        Choice("Exponential", 2), Choice("LCM", 9),
    )
    private val sizes = listOf(Choice("384", 384), Choice("512", 512), Choice("640", 640), Choice("768", 768),
        Choice("256 (fastest)", 256))
    private data class Style(val label: String, val tags: String, val negative: String) {
        override fun toString() = label
    }
    private val styles = listOf(
        Style("None", "", ""),
        Style("Anime", "anime style, cel shading, clean lineart, vibrant colors, detailed eyes, key visual, high quality illustration",
            "photo, realistic, 3d render"),
        Style("Cartoon", "cartoon character, bold outlines, flat colors, expressive, animated series style, clean background",
            "photo, realistic, 3d render, noisy"),
        Style("3D game character", "stylized 3d video game character, full body, character concept art, unreal engine render, soft studio lighting, high detail",
            "photo, flat, sketch, cropped"),
        Style("Pixel art", "pixel art, 16-bit retro video game sprite, limited palette, crisp pixels, full body",
            "blurry, smooth shading, photo, realistic, 3d"),
        Style("Chibi", "chibi, cute super deformed character, big head, small body, kawaii, simple background",
            "photo, realistic, tall"),
        Style("Comic book", "comic book character, bold ink lines, halftone shading, dynamic pose, vivid colors",
            "photo, realistic, 3d render"),
    )

    private val computes = listOf(
        Choice("CPU", 0), Choice("GPU (Vulkan)", 1), Choice("GPU + CPU for VAE (encode/decode)", 2),
        Choice("GPU for main model only (text + VAE on CPU)", 3),
    )
    private val areas = listOf(Choice("Only masked", 1), Choice("Whole image", 0))
    private val weightTypes = listOf(
        Choice("F16 (recommended)", 1), Choice("As stored in file", -1),
        Choice("Q8_0 (less RAM)", 8), Choice("Q5_0", 6), Choice("Q4_0 (least RAM)", 2),
    )

    private lateinit var canvas: MaskCanvasView
    private lateinit var txtStatus: TextView
    private lateinit var txtModel: TextView
    private lateinit var txtLog: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnGenerate: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button
    private lateinit var btnUseResult: Button
    private lateinit var btnLoadModel: Button
    private lateinit var btnBrush: Button
    private lateinit var btnToggle: Button
    private lateinit var editPrompt: EditText
    private lateinit var editNegative: EditText
    private lateinit var editSteps: EditText
    private lateinit var editCfg: EditText
    private lateinit var editStrength: EditText
    private lateinit var editSeed: EditText
    private lateinit var editThreads: EditText
    private lateinit var editClipSkip: EditText
    private lateinit var spinSampler: Spinner
    private lateinit var spinScheduler: Spinner
    private lateinit var spinSize: Spinner
    private lateinit var spinArea: Spinner
    private lateinit var spinWeights: Spinner
    private lateinit var spinCompute: Spinner
    private lateinit var chkGpuSafe: CheckBox
    private lateinit var chkFastVae: CheckBox
    private var loadedWithTae = false
    private var loadedIsInpaint = false
    private lateinit var spinStyle: Spinner
    private lateinit var btnTabInpaint: Button
    private lateinit var btnTabCreate: Button
    /** 0 = Inpaint tab, 1 = Stylize tab. */
    private var stylizeSource: Bitmap? = null
    private var mode = 0   // 0 = Inpaint, 1 = Img2Img, 2 = Text to image
    // Img2Img and Text each keep their own results (index = mode).
    private val galResults = Array(4) { mutableListOf<Bitmap>() }
    private val galSeeds = Array(4) { mutableListOf<Long>() }
    private val galIndex = IntArray(4)
    // Video tab (mode 3): the last clip's raw frames, kept for saving as a GIF.
    private var videoBytes: ByteArray? = null
    private var videoW = 0
    private var videoH = 0
    private val videoFps = 8
    private var loadedWithMotion = false
    private val frameChoices = listOf(Choice("8 frames (1 second)", 8), Choice("12 frames (1.5 seconds)", 12),
        Choice("16 frames (2 seconds)", 16))
    private val createResults get() = galResults[mode]
    private val createSeeds get() = galSeeds[mode]
    private var createIndex
        get() = galIndex[mode]
        set(v) { galIndex[mode] = v }
    private lateinit var spinTxtSize: Spinner
    private lateinit var btnTabText: Button
    private val txtSizes = listOf(
        Choice("512×512", 0), Choice("384×512 portrait", 1), Choice("512×384 landscape", 2),
        Choice("512×768 portrait", 3), Choice("768×512 landscape", 4), Choice("384×384 fast", 5),
    )
    private val txtDims = listOf(512 to 512, 384 to 512, 512 to 384, 512 to 768, 768 to 512, 384 to 384)
    private var loadedBackend = "cpu"
    private lateinit var chkBlend: CheckBox
    private lateinit var chkFlash: CheckBox
    private lateinit var chkMmap: CheckBox
    private lateinit var chkTiling: CheckBox

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private var modelFile: File? = null
    private var loraFile: File? = null
    private lateinit var txtLora: TextView
    private lateinit var editLoraStrength: EditText
    private var loadedModelName: String? = null
    private var busy = false
    private var lastSeed = -1L
    private val results = mutableListOf<Bitmap>()
    private val resultSeeds = mutableListOf<Long>()
    private var resultIndex = 0
    private var batchSize = 1
    private var stopRequested = false
    private lateinit var editImages: EditText
    private lateinit var txtResult: TextView
    private lateinit var rowResults: View
    private val logBuffer = StringBuilder()

    private val poller = object : Runnable {
        override fun run() {
            pollNative()
            if (busy) ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        applyGpuEnv()
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        setupSpinners()
        restorePrefs()
        setupListeners()
        showPreviousSession()
        checkGpuCrashMarker()
        // The queue only lives for one session; drop photos left over from a previous one.
        try { queueDir().listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }

        thread {
            val info = (try { SdNative.systemInfo() } catch (t: Throwable) { "native library failed to load: $t" }) +
                "\nGPU safe mode: " + (if (prefs.getBoolean("gpuSafe", true)) "on" else "off") +
                " · full precision: " + (if (prefs.getBoolean("gpuF32", false)) "on" else "off") +
                (if (startupNotes.isEmpty()) "\nGPU driver: system" else "\n" + startupNotes.trimEnd())
            ui.post { appendLog(info) }
        }
        handleIncomingIntent(intent)
        applyMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private var visible = false

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        super.onStop()
        visible = false
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !busy) {
            SdNative.freeModel()
        }
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        canvas = findViewById(R.id.canvas)
        txtStatus = findViewById(R.id.txtStatus)
        txtModel = findViewById(R.id.txtModel)
        txtLog = findViewById(R.id.txtLog)
        progress = findViewById(R.id.progress)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnCancel = findViewById(R.id.btnCancel)
        btnSave = findViewById(R.id.btnSave)
        btnUseResult = findViewById(R.id.btnUseResult)
        btnLoadModel = findViewById(R.id.btnLoadModel)
        btnBrush = findViewById(R.id.btnBrush)
        btnToggle = findViewById(R.id.btnToggle)
        editPrompt = findViewById(R.id.editPrompt)
        editNegative = findViewById(R.id.editNegative)
        editSteps = findViewById(R.id.editSteps)
        editCfg = findViewById(R.id.editCfg)
        editStrength = findViewById(R.id.editStrength)
        editSeed = findViewById(R.id.editSeed)
        editThreads = findViewById(R.id.editThreads)
        editClipSkip = findViewById(R.id.editClipSkip)
        spinSampler = findViewById(R.id.spinSampler)
        spinScheduler = findViewById(R.id.spinScheduler)
        spinSize = findViewById(R.id.spinSize)
        spinArea = findViewById(R.id.spinArea)
        spinWeights = findViewById(R.id.spinWeights)
        spinCompute = findViewById(R.id.spinCompute)
        chkGpuSafe = findViewById(R.id.chkGpuSafe)
        chkFastVae = findViewById(R.id.chkFastVae)
        txtLora = findViewById(R.id.txtLora)
        editLoraStrength = findViewById(R.id.editLoraStrength)
        spinStyle = findViewById(R.id.spinStyle)
        btnTabInpaint = findViewById(R.id.btnTabInpaint)
        btnTabCreate = findViewById(R.id.btnTabCreate)
        btnTabText = findViewById(R.id.btnTabText)
        spinTxtSize = findViewById(R.id.spinTxtSize)
        chkBlend = findViewById(R.id.chkBlend)
        chkFlash = findViewById(R.id.chkFlash)
        chkMmap = findViewById(R.id.chkMmap)
        chkTiling = findViewById(R.id.chkTiling)
        editImages = findViewById(R.id.editImages)
        txtResult = findViewById(R.id.txtResult)
        rowResults = findViewById(R.id.rowResults)
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun <T> adapter(items: List<T>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun setupSpinners() {
        spinSampler.adapter = adapter(samplers)
        spinScheduler.adapter = adapter(schedulers)
        spinSize.adapter = adapter(sizes)
        spinArea.adapter = adapter(areas)
        spinWeights.adapter = adapter(weightTypes)
        spinCompute.adapter = adapter(computes)
        spinStyle.adapter = adapter(styles)
        spinTxtSize.adapter = adapter(txtSizes)
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnImage).setOnClickListener { if (!restoringBlocks()) pickImage(REQ_IMAGE) }
        btnBrush.setOnClickListener {
            canvas.erasing = !canvas.erasing
            btnBrush.text = if (canvas.erasing) "Eraser" else "Brush"
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { canvas.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { canvas.clearMask() }
        findViewById<Button>(R.id.btnInvert).setOnClickListener { canvas.invertMask() }
        findViewById<Button>(R.id.btnFit).setOnClickListener { canvas.resetView() }
        btnToggle.setOnClickListener {
            if (canvas.result != null && !restoringBlocks()) {
                canvas.showResult = !canvas.showResult
                updateUi()
            }
        }
        findViewById<SeekBar>(R.id.seekBrush).apply {
            canvas.brushSizePx = progress * resources.displayMetrics.density / 2f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    canvas.brushSizePx = p * resources.displayMetrics.density / 2f
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        btnGenerate.setOnClickListener { generate() }
        btnCancel.setOnClickListener {
            // In a queue, the first tap lets the current photo finish and then stops.
            if (queueRunning && !queueStop) {
                queueStop = true
                btnCancel.text = "Stop now"
                appendLog("Stopping the queue after the current photo…")
            } else if (queueRunning) {
                SdNative.cancel(false)
                txtStatus.text = "Cancelling…"
            }
            // With several images, the first tap keeps the ones already finished.
            else if (batchSize > 1 && !stopRequested) {
                stopRequested = true
                SdNative.cancel(true)
                btnCancel.text = "Stop now"
                appendLog("Stopping after the current image…")
            } else {
                SdNative.cancel(false)
                txtStatus.text = "Cancelling…"
            }
        }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { showResultAt(currentIndex() - 1) }
        findViewById<Button>(R.id.btnNext).setOnClickListener { showResultAt(currentIndex() + 1) }
        canvas.onSwipe = { dir -> showResultAt(currentIndex() + dir) }
        btnTabInpaint.setOnClickListener { switchMode(0) }
        btnTabCreate.setOnClickListener { switchMode(1) }
        btnTabText.setOnClickListener { switchMode(2) }
        findViewById<Button>(R.id.btnTabVideo).setOnClickListener { switchMode(3) }
        findViewById<Button>(R.id.btnQueueAdd).setOnClickListener { addToQueue() }
        findViewById<Button>(R.id.btnQueueRun).setOnClickListener { runQueue() }
        findViewById<Button>(R.id.btnQueueClear).setOnClickListener { clearQueue() }
        findViewById<Button>(R.id.btnChooseMotion).setOnClickListener { if (!busy) pickFile(REQ_MOTION) }
        findViewById<Spinner>(R.id.spinFrames).apply {
            adapter = adapter(frameChoices)
            setSelection(prefs.getInt("frames", 0).coerceIn(0, frameChoices.size - 1))
        }
        findViewById<Button>(R.id.btnStylePhoto).setOnClickListener { pickImage(REQ_STYLE_IMAGE) }
        btnSave.setOnClickListener { saveResult() }
        btnUseResult.setOnClickListener {
            if (restoringBlocks()) return@setOnClickListener
            if (mode != 0) {
                val bmp = createResults.getOrNull(createIndex) ?: return@setOnClickListener
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
                switchMode(0)
                clearResults()
                canvas.setImage(copy)
                txtStatus.text = "Paint over the part you want to change."
                updateUi()
                return@setOnClickListener
            }
            canvas.result?.let {
                val keep = it
                results.filter { r -> r !== keep }.forEach { r -> r.recycle() }
                results.clear(); resultSeeds.clear()
                canvas.setImage(keep)
                updateUi()
                toast("Result is now the input image")
            }
        }
        btnLastSeed.setOnClickListener {
            val s = (if (mode != 0) createSeeds.getOrNull(createIndex) else resultSeeds.getOrNull(resultIndex)) ?: lastSeed
            if (s >= 0) editSeed.setText(s.toString())
        }
        findViewById<Button>(R.id.btnChooseModel).setOnClickListener { pickModel() }
        btnLoadModel.setOnClickListener { loadModel() }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { toggleRestore() }
        findViewById<Button>(R.id.btnCheckpoints).setOnClickListener { if (!busy) showCheckpoints() }
        findViewById<Button>(R.id.btnPresets).setOnClickListener { if (!busy) showPresets() }
        findViewById<Button>(R.id.btnDescribe).setOnClickListener { if (!busy) startDescribe() }
        findViewById<Button>(R.id.btnDescribeFiles).setOnClickListener {
            if (busy) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_DESCRIBE_FILES)
        }
        findViewById<Button>(R.id.btnDescribeRemove).setOnClickListener {
            if (busy) return@setOnClickListener
            Describer.remove(this)
            appendLog("Describe model files removed.")
            updateUi()
        }
        findViewById<Button>(R.id.btnSavePreset).setOnClickListener { if (!busy) askSavePreset() }
        findViewById<Button>(R.id.btnChooseLora).setOnClickListener { pickFile(REQ_LORA) }
        findViewById<Button>(R.id.btnInstallDriver).setOnClickListener { if (!busy) pickFile(REQ_DRIVER) }
        findViewById<Button>(R.id.btnRemoveDriver).setOnClickListener {
            if (busy) return@setOnClickListener
            GpuDriver.remove(this)
            updateUi()
            appendLog("Custom GPU driver removed. Close and reopen the app to go back to the system driver.")
        }
        findViewById<Button>(R.id.btnClearLora).setOnClickListener {
            if (busy || loraFile == null) return@setOnClickListener
            val removedLcm = loraFile?.name?.contains("lcm", ignoreCase = true) == true
            loraFile?.delete()
            loraFile = null
            savePrefs()
            if (!removedLcm) {
                appendLog("LoRA removed.")
            } else if (isLcmSetup()) {
                appendLog("LoRA removed. The loaded model is an LCM model, so keep LCM, about 6 steps and CFG 1.0.")
            } else {
                applyNormalSettings()
                appendLog("LoRA removed. Settings switched to Euler a, 20 steps, CFG 7 for a normal model.")
            }
            updateUi()
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("StableAndroid log", logBuffer.toString()))
            toast("Log copied")
        }
    }

    private val btnLastSeed: Button get() = findViewById(R.id.btnLastSeed)

    // ---------------------------------------------------------------- prefs

    private fun restorePrefs() {
        mode = prefs.getInt("mode", 0)
        loadPromptsFor(mode)
        editSteps.setText(prefs.getString("steps", "20"))
        editCfg.setText(prefs.getString("cfg", "7"))
        editSeed.setText(prefs.getString("seed", "-1"))
        editImages.setText(prefs.getString("images", "3"))
        editClipSkip.setText(prefs.getString("clipSkip", "-1"))
        val cores = try { SdNative.physicalCores() } catch (t: Throwable) { 4 }
        editThreads.setText(prefs.getString("threads", cores.coerceIn(1, 4).toString()))
        spinSampler.setSelection(prefs.getInt("sampler", 0))
        spinScheduler.setSelection(prefs.getInt("scheduler", 0))
        spinSize.setSelection(prefs.getInt("size", 1))
        spinArea.setSelection(prefs.getInt("area", 0))
        spinWeights.setSelection(prefs.getInt("weights", 0))
        spinCompute.setSelection(prefs.getInt("compute", 0))
        chkGpuSafe.isChecked = prefs.getBoolean("gpuSafe", true)
        findViewById<CheckBox>(R.id.chkGpuF32).apply {
            isChecked = prefs.getBoolean("gpuF32", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("gpuF32", on).apply()
                toast("Close and reopen the app for this to take effect")
            }
        }
        chkGpuSafe.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("gpuSafe", on).apply()
            toast("Close and reopen the app for this to take effect")
        }
        spinStyle.setSelection(prefs.getInt("style", 1))
        spinTxtSize.setSelection(prefs.getInt("txtSize", 0))
        chkBlend.isChecked = prefs.getBoolean("blend", true)
        findViewById<CheckBox>(R.id.chkColorMatch).isChecked = prefs.getBoolean("colorMatch", true)
        // Looked up here, not inside apply{}: there findViewById would search inside the SeekBar.
        val featherLabel: TextView = findViewById(R.id.txtFeather)
        findViewById<SeekBar>(R.id.seekFeather).apply {
            progress = prefs.getInt("feather", 12)
            val label = featherLabel
            fun show(p: Int) { label.text = "Edge feather: " + when { p < 5 -> "sharp"; p < 25 -> "normal"; p < 60 -> "soft"; else -> "very soft" } }
            show(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = show(p)
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        chkFlash.isChecked = prefs.getBoolean("flash", true)
        chkMmap.isChecked = prefs.getBoolean("mmap", false)
        chkTiling.isChecked = prefs.getBoolean("tiling", true)
        chkFastVae.isChecked = prefs.getBoolean("fastVae", true)
        findViewById<CheckBox>(R.id.chkSharpen).isChecked = prefs.getBoolean("sharpen", false)
        findViewById<CheckBox>(R.id.chkAutoSave).isChecked = prefs.getBoolean("autoSave", true)
        editLoraStrength.setText(prefs.getString("loraStrength", "1.0"))
        prefs.getString("loraPath", null)?.let { p ->
            val f = File(p)
            if (f.exists()) loraFile = f
        }
        prefs.getString("modelPath", null)?.let { p ->
            val f = File(p)
            if (f.exists()) modelFile = f
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString(promptKey(mode), editPrompt.text.toString())
            .putString(negativeKey(mode), editNegative.text.toString())
            .putString("steps", editSteps.text.toString())
            .putString("cfg", editCfg.text.toString())
            .putString(strengthKey(mode), editStrength.text.toString())
            .putString("seed", editSeed.text.toString())
            .putString("images", editImages.text.toString())
            .putString("clipSkip", editClipSkip.text.toString())
            .putString("threads", editThreads.text.toString())
            .putInt("sampler", spinSampler.selectedItemPosition)
            .putInt("scheduler", spinScheduler.selectedItemPosition)
            .putInt("size", spinSize.selectedItemPosition)
            .putInt("area", spinArea.selectedItemPosition)
            .putInt("weights", spinWeights.selectedItemPosition)
            .putInt("compute", spinCompute.selectedItemPosition)
            .putInt("style", spinStyle.selectedItemPosition)
            .putInt("txtSize", spinTxtSize.selectedItemPosition)
            .putInt("mode", mode)
            .putBoolean("blend", chkBlend.isChecked)
            .putBoolean("colorMatch", findViewById<CheckBox>(R.id.chkColorMatch).isChecked)
            .putInt("feather", findViewById<SeekBar>(R.id.seekFeather).progress)
            .putBoolean("flash", chkFlash.isChecked)
            .putBoolean("mmap", chkMmap.isChecked)
            .putBoolean("tiling", chkTiling.isChecked)
            .putBoolean("fastVae", chkFastVae.isChecked)
            .putBoolean("sharpen", findViewById<CheckBox>(R.id.chkSharpen).isChecked)
            .putBoolean("autoSave", findViewById<CheckBox>(R.id.chkAutoSave).isChecked)
            .putString("modelPath", modelFile?.absolutePath)
            .putString("loraPath", loraFile?.absolutePath)
            .putString("loraStrength", editLoraStrength.text.toString())
            .apply()
    }

    // ---------------------------------------------------------------- pickers

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == REQ_DESCRIBE_FILES && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
            if (uris.isEmpty()) data.data?.let { uris += it }
            installDescribeFiles(uris)
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_DESCRIBE_IMAGE -> thread {
                try {
                    val bmp = ImageOps.load(contentResolver, uri, 1024)
                    ui.post { runDescribe(bmp, recycleAfter = true) }
                } catch (t: Throwable) { ui.post { toast("Couldn't open image: ${t.message}") } }
            }
            REQ_IMAGE -> openImage(uri)
            REQ_STYLE_IMAGE -> openStylizeImage(uri)
            REQ_MODEL -> importModel(uri)
            REQ_LORA -> importLora(uri)
            REQ_DRIVER -> installDriver(uri)
            REQ_MOTION -> importMotion(uri)
            REQ_RESTORE_EDITED -> {
                pendingRestoreEdited = uri
                toast("Now choose the original photo it was made from")
                pickImage(REQ_RESTORE_ORIGINAL)
            }
            REQ_RESTORE_ORIGINAL -> {
                val edited = pendingRestoreEdited
                pendingRestoreEdited = null
                if (edited != null) restoreFromFiles(edited, uri)
            }
            REQ_MODEL_FOLDER -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) { }
                prefs.edit().putString("modelFolder", uri.toString()).apply()
                showCheckpoints()
            }
        }
    }

    private fun pickImage(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    private fun pickModel() = pickFile(REQ_MODEL)

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        uri?.let {
            if (mode == 2) switchMode(0)
            if (mode == 3) { openStylizeImage(it); return }
            if (mode == 1) openStylizeImage(it) else openImage(it)
        }
    }

    private fun openStylizeImage(uri: Uri) {
        thread {
            try {
                val bmp = ImageOps.load(contentResolver, uri)
                ui.post {
                    stylizeSource?.recycle()
                    stylizeSource = bmp
                    clearCreateResults()
                    refreshStylizePreview()
                    txtStatus.text = "Photo ${bmp.width}×${bmp.height}. Describe the change or pick a style, then tap Generate."
                    updateUi()
                }
            } catch (t: Throwable) {
                ui.post { toast("Couldn't open image: ${t.message}") }
            }
        }
    }

    private fun clearCreateResults() {
        canvas.preview = null
        createResults.forEach { it.recycle() }
        createResults.clear(); createSeeds.clear(); createIndex = 0
    }

    /** Stylize tab: show the current result (hold to see the photo), or the photo itself. */
    private fun refreshStylizePreview() {
        if (mode == 0) return
        val res = createResults.getOrNull(createIndex)
        val src = if (mode == 1 || mode == 3) stylizeSource else null
        canvas.preview = res ?: src
        canvas.previewOriginal = if (res != null) src else null
    }

    private fun openImage(uri: Uri) {
        thread {
            try {
                val bmp = ImageOps.load(contentResolver, uri)
                ui.post {
                    // A new photo ends any restore in progress before its bitmaps are released.
                    if (canvas.restoring) {
                        canvas.finishRestore()?.recycle()
                        restoreOrigTemp?.recycle(); restoreOrigTemp = null
                    }
                    clearResults()
                    canvas.setImage(bmp)
                    txtStatus.text = "Image ${bmp.width}×${bmp.height}. Paint over the area to change."
                    updateUi()
                }
            } catch (t: Throwable) {
                ui.post { toast("Couldn't open image: ${t.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- model

    private fun importModel(uri: Uri) {
        if (busy) return
        val (name, size) = ModelStore.queryName(this, uri)
        appendLog("Chosen: $name" + if (size > 0) " (${ModelStore.gb(size)})" else "")
        setBusy(true, "Copying $name into app storage…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        thread(name = "sd-import") {
            try {
                // Release the old model before deleting its file.
                SdNative.freeModel()
                ui.post { loadedModelName = null }
                val file = ModelStore.import(this, uri, "models", keepOthers = true) { copied, total ->
                    ui.post {
                        if (total > 0) {
                            progress.isIndeterminate = false
                            progress.max = 1000
                            progress.progress = (copied * 1000 / total).toInt()
                            txtStatus.text = "Copying model… ${ModelStore.gb(copied)} / ${ModelStore.gb(total)}"
                        } else {
                            txtStatus.text = "Copying model… ${ModelStore.gb(copied)}"
                        }
                    }
                }
                ui.post {
                    modelFile = file
                    savePrefs()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setBusy(false, "Model copied")
                    loadModel()
                }
            } catch (t: Throwable) {
                ui.post {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setBusy(false, "Copy failed: ${t.message}")
                }
            }
        }
    }

    private fun importLora(uri: Uri) {
        if (busy) return
        val (name, size) = ModelStore.queryName(this, uri)
        if (size > 1024L * 1024 * 1024) {
            toast("That file is over 1 GB, so it looks like a full model, not a LoRA")
            return
        }
        setBusy(true, "Copying LoRA $name…")
        thread(name = "sd-lora-import") {
            try {
                val file = ModelStore.import(this, uri, "loras") { _, _ -> }
                ModelStore.checkFile(file)?.let { file.delete(); throw IllegalStateException(it) }
                ui.post {
                    loraFile = file
                    appendLog("LoRA: ${file.name} (${file.length() / (1024 * 1024)} MB). It is applied on the next run.")
                    if (file.name.contains("lcm", ignoreCase = true)) {
                        spinSampler.setSelection(samplers.indexOfFirst { it.label == "LCM" })
                        spinScheduler.setSelection(0)
                        editSteps.setText("6")
                        editCfg.setText("1.0")
                        appendLog("LCM LoRA detected: set sampler LCM, 6 steps, CFG 1.0.")
                    } else {
                        appendLog("If this is an LCM LoRA, set Sampler to LCM, Steps to 6 and CFG to 1.0 yourself " +
                            "(the file name doesn't say, so the settings were left alone).")
                    }
                    savePrefs()
                    setBusy(false, "LoRA ready")
                }
            } catch (t: Throwable) {
                ui.post { setBusy(false, "LoRA failed: ${t.message}") }
            }
        }
    }

    private fun installDriver(uri: Uri) {
        thread {
            try {
                val info = GpuDriver.install(this, uri)
                prefs.edit().putBoolean("driverEnabled", true).apply()
                ui.post {
                    appendLog("Installed GPU driver: ${info.name}. Close and reopen the app, then choose " +
                        "Run on: GPU (Vulkan) and tap Load.")
                    toast("Driver installed. Close and reopen the app to use it.")
                    updateUi()
                }
            } catch (t: Throwable) {
                ui.post { appendLog("E Driver install failed: ${t.message}"); toast("Driver install failed: ${t.message}") }
            }
        }
    }

    private fun loraStrength() = (editLoraStrength.text.toString().toFloatOrNull() ?: 1f).coerceIn(-2f, 2f)

    private fun loadModel() {
        val file = modelFile ?: run { toast("Choose a model file first"); return }
        if (busy) return
        val threads = editThreads.text.toString().toIntOrNull() ?: 4
        val wtype = (spinWeights.selectedItem as Choice).value
        val flash = chkFlash.isChecked
        val mmap = chkMmap.isChecked
        val computeMode = (spinCompute.selectedItem as Choice).value
        val fastVae = chkFastVae.isChecked
        val videoTabAtLoad = mode == 3
        if (videoTabAtLoad && motionFile() == null) toast("No motion module chosen yet, so video won't work until you add one")
        // ggml looks at the GPU while loading even for CPU runs, so a custom driver counts as "in use".
        val wantGpu = computeMode != 0
        if (customDriverActive) markGpuInFlight(true)
        val name = file.name
        setBusy(true, if (wantGpu) "Loading $name on the GPU…" else "Loading $name…")
        val started = System.currentTimeMillis()
        thread(name = "sd-load") {
            try {
                ModelStore.checkFile(file)?.let { throw IllegalStateException(it) }
                SdNative.freeModel()
                var backend = "cpu"
                var gpuName = ""
                if (wantGpu) {
                    markGpuInFlight(true)
                    val devices = SdNative.listDevices().lines().filter { it.isNotBlank() }
                    ui.post { appendLog("Devices:\n" + devices.joinToString("\n") { "  " + it.replace('\t', ' ') }) }
                    val vk = devices.firstOrNull { it.startsWith("vulkan", ignoreCase = true) }
                    if (vk != null) {
                        val dev = vk.substringBefore('\t').lowercase()
                        backend = when (computeMode) {
                            2 -> "all=$dev,vae=cpu"
                            3 -> "all=cpu,diffusion=$dev"
                            else -> dev
                        }
                        gpuName = vk.substringAfter('\t', "")
                    } else {
                        ui.post { appendLog("No usable Vulkan GPU found. Using the CPU instead.") }
                    }
                }
                val taesd = if (fastVae) taesdFile()?.absolutePath else null
                if (fastVae && taesd == null) ui.post { appendLog("Fast VAE file missing from this build; using the normal VAE.") }
                // The motion module costs memory, so it is only loaded when Load is tapped on the Video tab.
                val motion = if (videoTabAtLoad) motionFile()?.absolutePath else null
                val version = SdNative.loadModel(file.absolutePath, null, threads, wtype, flash, mmap, backend, taesd, motion)
                loadedWithMotion = motion != null
                loadedWithTae = taesd != null
                loadedIsInpaint = version.contains("Inpaint", ignoreCase = true)
                markGpuInFlight(false)
                loadedBackend = backend
                val secs = (System.currentTimeMillis() - started) / 1000
                ui.post {
                    loadedModelName = name
                    val where = if (backend == "cpu") "CPU" else "GPU ${gpuName.ifEmpty { backend }}"
                    setBusy(false, "Loaded $version on $where in ${secs}s" + if (loadedWithTae) " · fast VAE" else "")
                    if (name.contains("lcm", ignoreCase = true) && prefs.getString("autoTuned", null) != name) {
                        prefs.edit().putString("autoTuned", name).apply()
                        spinSampler.setSelection(samplers.indexOfFirst { it.label == "LCM" })
                        spinScheduler.setSelection(0)
                        editSteps.setText("6")
                        editCfg.setText("1.5")
                        appendLog("LCM model detected: set sampler LCM, 6 steps, CFG 1.5 (LCM models need 4–8 steps and CFG 1–2).")
                    }
                    if (!version.contains("Inpaint", ignoreCase = true)) {
                        appendLog("Note: \"$version\" isn't an inpainting model. It will still repaint the " +
                            "masked area, but an SD 1.5 *inpainting* checkpoint blends far better (use Denoise ~0.6–0.8).")
                        if (mode == 0) editStrength.setText("0.75")
                        else prefs.edit().putString(strengthKey(0), "0.75").apply()
                    }
                    updateUi()
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                ui.post {
                    loadedModelName = null
                    appendLog("E ${t.message}")
                    setBusy(false, "Load failed: ${t.message}")
                    updateUi()
                }
            }
        }
    }

    // ---------------------------------------------------------------- generate

    // ---------------------------------------------------------------- checkpoint list

    private fun modelFolder(): Uri? = prefs.getString("modelFolder", null)?.let { Uri.parse(it) }

    private fun chooseModelFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_MODEL_FOLDER)
    }

    /** Lists checkpoints in the chosen folder plus the app's own copies; tap one to load it. */
    private fun showCheckpoints() {
        val folder = modelFolder()
        thread {
            val entries = ModelStore.listCheckpoints(this, folder)
            ui.post {
                val labels = entries.map { e ->
                    val state = when {
                        e.local != null && e.local.name == loadedModelName -> "loaded"
                        e.local != null -> "ready"
                        else -> "needs copying first"
                    }
                    "${e.name}\n${ModelStore.gb(e.size)} · $state"
                }
                val b = android.app.AlertDialog.Builder(this)
                    .setTitle(if (folder == null) "Checkpoints (no folder chosen)" else "Checkpoints")
                    .setPositiveButton("Choose folder") { _, _ -> chooseModelFolder() }
                    .setNegativeButton("Close", null)
                if (entries.isEmpty()) {
                    b.setMessage(if (folder == null) "Choose the folder where you keep your .safetensors checkpoints."
                        else "No checkpoints found directly in that folder (files under 900 MB and sub-folders are ignored).")
                } else {
                    b.setItems(labels.toTypedArray()) { _, i ->
                        val e = entries[i]
                        when {
                            e.local != null -> { modelFile = e.local; savePrefs(); updateUi(); loadModel() }
                            e.uri != null -> importModel(e.uri)
                        }
                    }
                    if (ModelStore.library(this).isNotEmpty()) b.setNeutralButton("Delete a copy") { _, _ -> showDeleteCopies() }
                }
                b.show()
            }
        }
    }

    private fun showDeleteCopies() {
        val copies = ModelStore.library(this)
        val labels = copies.map { "${it.name}\n${ModelStore.gb(it.length())}" + if (it.name == loadedModelName) " · loaded" else "" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete which copy from the app?")
            .setItems(labels.toTypedArray()) { _, i ->
                val f = copies[i]
                if (f.name == loadedModelName) {
                    SdNative.freeModel()
                    loadedModelName = null
                }
                if (modelFile?.absolutePath == f.absolutePath) modelFile = null
                f.delete()
                savePrefs()
                appendLog("Deleted the app's copy of ${f.name}. Your original file is untouched.")
                updateUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- describe (Florence-2)

    private fun installDescribeFiles(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        setBusy(true, "Copying describe files…")
        thread(name = "describe-install") {
            val msg = try { Describer.install(this, uris) } catch (t: Throwable) { "E Describe files: ${t.message}" }
            ui.post { appendLog(msg); setBusy(false, msg.take(80)); toast(msg) }
        }
    }

    private var describeDetail = 1

    private fun startDescribe() {
        if (!Describer.installed(this)) {
            toast("Add the describe model first: Model section → Choose describe files")
            appendLog("Describe needs 4 Florence-2 files (vision_encoder, embed_tokens, encoder_model, decoder_model). " +
                "Still needed: ${Describer.missing(this).joinToString()}")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("How much detail?")
            .setItems(arrayOf("Short", "Detailed", "Very detailed (paragraph)")) { _, which ->
                describeDetail = which
                val src = when (mode) { 0 -> canvas.image; 1, 3 -> stylizeSource; else -> null }
                if (src != null) runDescribe(src, recycleAfter = false)
                else {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_DESCRIBE_IMAGE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runDescribe(image: Bitmap, recycleAfter: Boolean) {
        if (busy) return
        val threads = editThreads.text.toString().toIntOrNull() ?: 4
        val detail = describeDetail
        // Work on a small private copy so the source can't change underneath us.
        val scale = 768f / maxOf(image.width, image.height)
        val copy = Bitmap.createScaledBitmap(image, maxOf(1, (image.width * scale).toInt()),
            maxOf(1, (image.height * scale).toInt()), true).let {
                if (it === image) image.copy(Bitmap.Config.ARGB_8888, false) else it
            }
        if (recycleAfter) image.recycle()
        setBusy(true, "Describing…")
        val started = System.currentTimeMillis()
        thread(name = "describe") {
            try {
                val text = Describer.describe(this, copy, detail, threads) { stage -> ui.post { txtStatus.text = stage } }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    if (text.isBlank()) {
                        setBusy(false, "Describe returned nothing")
                    } else {
                        val old = editPrompt.text.toString().trim()
                        if (old.isNotEmpty()) appendLog("Previous prompt: $old")
                        editPrompt.setText(text)
                        appendLog("Described in ${"%.1f".format(secs)}s: $text")
                        setBusy(false, "Description added to the prompt (${"%.0f".format(secs)}s)")
                    }
                }
            } catch (t: Throwable) {
                ui.post { appendLog("E Describe failed: ${t.message}"); setBusy(false, "Describe failed: ${t.message}") }
            } finally {
                copy.recycle()
            }
        }
    }

    // ---------------------------------------------------------------- presets (one list per tab)

    private fun tabName() = when (mode) { 0 -> "Inpaint"; 1 -> "Img2Img"; 2 -> "Text"; else -> "Video" }

    private fun presetStore(): org.json.JSONObject =
        try { org.json.JSONObject(prefs.getString("presets_$mode", "{}") ?: "{}") }
        catch (_: Throwable) { org.json.JSONObject() }

    private fun presetNames() = presetStore().keys().asSequence().sortedBy { it.lowercase() }.toList()

    private fun currentSettings() = org.json.JSONObject().apply {
        put("negative", editNegative.text.toString())
        put("sampler", spinSampler.selectedItemPosition)
        put("scheduler", spinScheduler.selectedItemPosition)
        put("steps", editSteps.text.toString())
        put("cfg", editCfg.text.toString())
        put("images", editImages.text.toString())
        put("clipSkip", editClipSkip.text.toString())
        put("style", spinStyle.selectedItemPosition)
        if (mode != 2) {
            put("strength", editStrength.text.toString())
            put("size", spinSize.selectedItemPosition)
        } else {
            put("txtSize", spinTxtSize.selectedItemPosition)
        }
        if (mode == 0) {
            put("area", spinArea.selectedItemPosition)
            put("blend", chkBlend.isChecked)
            put("colorMatch", findViewById<CheckBox>(R.id.chkColorMatch).isChecked)
            put("feather", findViewById<SeekBar>(R.id.seekFeather).progress)
        }
    }

    private fun applySettings(j: org.json.JSONObject) {
        fun sel(sp: Spinner, key: String) {
            if (j.has(key)) sp.setSelection(j.getInt(key).coerceIn(0, sp.count - 1))
        }
        fun txt(e: EditText, key: String) { if (j.has(key)) e.setText(j.getString(key)) }
        txt(editNegative, "negative"); txt(editSteps, "steps"); txt(editCfg, "cfg")
        txt(editImages, "images"); txt(editClipSkip, "clipSkip"); txt(editStrength, "strength")
        sel(spinSampler, "sampler"); sel(spinScheduler, "scheduler"); sel(spinStyle, "style")
        sel(spinSize, "size"); sel(spinTxtSize, "txtSize"); sel(spinArea, "area")
        if (j.has("blend")) chkBlend.isChecked = j.getBoolean("blend")
        if (j.has("colorMatch")) findViewById<CheckBox>(R.id.chkColorMatch).isChecked = j.getBoolean("colorMatch")
        if (j.has("feather")) findViewById<SeekBar>(R.id.seekFeather).progress = j.getInt("feather")
        savePrefs()
    }

    private fun askSavePreset() {
        val input = EditText(this).apply {
            hint = "Name, e.g. Fast LCM"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Save ${tabName()} settings as")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("Give the preset a name"); return@setPositiveButton }
                val store = presetStore()
                val replaced = store.has(name)
                store.put(name, currentSettings())
                prefs.edit().putString("presets_$mode", store.toString()).apply()
                toast(if (replaced) "Preset \"$name\" updated" else "Preset \"$name\" saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresets() {
        val names = presetNames()
        if (names.isEmpty()) {
            toast("No ${tabName()} presets yet. Set things up and tap Save settings.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${tabName()} presets")
            .setItems(names.toTypedArray()) { _, i ->
                applySettings(presetStore().getJSONObject(names[i]))
                toast("Applied \"${names[i]}\"")
            }
            .setNeutralButton("Delete…") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Delete which preset?")
                    .setItems(names.toTypedArray()) { _, i ->
                        val store = presetStore()
                        store.remove(names[i])
                        prefs.edit().putString("presets_$mode", store.toString()).apply()
                        toast("Deleted \"${names[i]}\"")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** True when the loaded model or the chosen LoRA is an LCM one (judged by file name). */
    private fun isLcmSetup() = loadedModelName?.contains("lcm", ignoreCase = true) == true ||
        loraFile?.name?.contains("lcm", ignoreCase = true) == true

    private fun applyLcmSettings() {
        spinSampler.setSelection(samplers.indexOfFirst { it.label == "LCM" })
        spinScheduler.setSelection(0)
        editSteps.setText("6")
        editCfg.setText("1.0")
    }

    private fun applyNormalSettings() {
        spinSampler.setSelection(samplers.indexOfFirst { it.label == "Euler a" })
        spinScheduler.setSelection(0)
        editSteps.setText("20")
        editCfg.setText("7")
    }

    /** One line describing the sampling settings, for the log. */
    private fun settingsLine(): String =
        "sampler ${(spinSampler.selectedItem as Choice).label}, scheduler ${(spinScheduler.selectedItem as Choice).label}, " +
            "steps ${editSteps.text}, CFG ${editCfg.text}, LoRA " +
            (loraFile?.let { "${it.name} @ ${loraStrength()}" } ?: "none")

    /**
     * Catches the two mismatches that waste a long run: the LCM sampler without an LCM
     * model/LoRA, and normal settings with one. Returns true if a dialog was shown.
     */
    private fun warnOnSettingsMismatch(run: () -> Unit): Boolean {
        val lcmSampler = (spinSampler.selectedItem as Choice).label == "LCM"
        val steps = editSteps.text.toString().toIntOrNull() ?: 20
        val cfg = editCfg.text.toString().toFloatOrNull() ?: 7f
        val lcm = isLcmSetup()
        // A LoRA whose name doesn't say what it is might be an LCM one: don't second-guess it.
        val unknownLora = loraFile != null && !lcm
        val (message, fix) = when {
            lcmSampler && !lcm && !unknownLora ->
                "The LCM sampler only works with an LCM model or the LCM LoRA, and neither is in use. " +
                    "Results will be poor.\n\nSwitch to Euler a, 20 steps, CFG 7? (Slower, but correct for this model. " +
                    "Or cancel and add the LCM LoRA.)" to { applyNormalSettings() }
            lcm && (!lcmSampler || cfg > 2.5f || steps > 12) ->
                "An LCM model or LoRA is in use, but the settings are for a normal model " +
                    "(sampler ${(spinSampler.selectedItem as Choice).label}, $steps steps, CFG $cfg). " +
                    "That is much slower and usually gives burnt images.\n\nSwitch to LCM, 6 steps, CFG 1.0?" to { applyLcmSettings() }
            else -> return false
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings don't match the model")
            .setMessage(message)
            .setPositiveButton("Fix and run") { _, _ -> fix(); skipSettingsCheck = true; run() }
            .setNeutralButton("Run anyway") { _, _ -> skipSettingsCheck = true; run() }
            .setNegativeButton("Cancel", null)
            .show()
        return true
    }

    private var skipSettingsCheck = false

    /** Everything one inpaint run needs, captured from the screen at the moment it was requested. */
    private class InpaintParams(
        val prompt: String, val negative: String, val steps: Int, val cfg: Float, val strength: Float,
        val seed: Long, val sampler: Int, val scheduler: Int, val size: Int, val onlyMasked: Boolean,
        val blend: Boolean, val colorMatch: Boolean, val feather: Float, val clipSkip: Int,
        val tiling: Boolean, val count: Int, val summary: String,
    )

    private fun readInpaintParams(): InpaintParams {
        var seed = editSeed.text.toString().toLongOrNull() ?: -1L
        if (seed < 0) seed = Random.nextLong(0, Int.MAX_VALUE.toLong())
        return InpaintParams(
            prompt = editPrompt.text.toString(),
            negative = editNegative.text.toString(),
            steps = (editSteps.text.toString().toIntOrNull() ?: 20).coerceIn(1, 150),
            cfg = editCfg.text.toString().toFloatOrNull() ?: 7f,
            strength = (editStrength.text.toString().toFloatOrNull() ?: 1f).coerceIn(0.01f, 1f),
            seed = seed,
            sampler = (spinSampler.selectedItem as Choice).value,
            scheduler = (spinScheduler.selectedItem as Choice).value,
            size = (spinSize.selectedItem as Choice).value,
            onlyMasked = (spinArea.selectedItem as Choice).value == 1,
            blend = chkBlend.isChecked,
            colorMatch = findViewById<CheckBox>(R.id.chkColorMatch).isChecked,
            // Slider 0–100 -> soft edge of 0.4%–20% of the mask's longer side.
            feather = 0.004f + 0.196f * (findViewById<SeekBar>(R.id.seekFeather).progress / 100f),
            clipSkip = editClipSkip.text.toString().toIntOrNull() ?: -1,
            tiling = chkTiling.isChecked && !loadedWithTae,
            count = (editImages.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8),
            summary = settingsLine(),
        )
    }

    /**
     * Inpaints on the worker thread, one image at a time, handing each finished full-size image
     * to [onImage] as soon as it is ready. Returns how many were made. If the user stops part
     * way, the images already finished are kept.
     */
    private fun inpaintCore(image: Bitmap, mask: Bitmap, p: InpaintParams, prefix: String = "",
                            onImage: (index: Int, result: Bitmap) -> Unit): Int {
        val job = ImageOps.prepare(image, mask, p.onlyMasked, p.size, 32, p.feather)
            ?: throw IllegalStateException("Paint a mask over the area you want to change first")
        ui.post {
            appendLog("Inpainting ${p.count}× ${job.width}×${job.height} from region " +
                "${job.region.toShortString()}, denoise ${p.strength}, seeds ${p.seed}–${p.seed + p.count - 1}\n  " + p.summary)
        }
        var made = 0
        try {
            for (k in 0 until p.count) {
                if (k > 0 && (stopRequested || queueStop)) break
                statusPrefix = prefix + if (p.count > 1) "Image ${k + 1}/${p.count} · " else ""
                if (loadedBackend != "cpu") markGpuInFlight(true)
                val out = try {
                    SdNative.inpaint(job.rgb, job.mask, job.width, job.height, p.prompt, p.negative,
                        p.steps, p.cfg, p.strength, p.seed + k, p.sampler, p.scheduler, p.clipSkip, p.tiling, 1,
                        loraFile?.absolutePath, loraStrength())
                } catch (t: Throwable) {
                    markGpuInFlight(false)
                    if (made > 0 && stopRequested) break   // cancelled after some were finished: keep those
                    throw t
                }
                markGpuInFlight(false)
                val gen = ImageOps.fromRgb(out, job.width, job.height)
                val f = ImageOps.composite(image, mask, job, gen, p.blend, p.colorMatch)
                gen.recycle()
                onImage(k, f)
                made++
            }
        } finally {
            statusPrefix = prefix
        }
        return made
    }

    private fun generate() {
        if (restoringBlocks()) return
        if (mode == 3) { createVideo(); return }
        if (mode != 0) { create(); return }
        if (busy) return
        if (loadedModelName == null) { toast("Load a model first"); return }
        if (!skipSettingsCheck && warnOnSettingsMismatch { generate() }) return
        skipSettingsCheck = false
        val image = canvas.image ?: run { toast("Open an image first"); return }
        val mask = canvas.mask ?: return
        // If a result is showing, inpaint on top of the original again.
        canvas.showResult = false

        val p = readInpaintParams()
        val maskCopy = mask.copy(Bitmap.Config.ALPHA_8, false)
        lastSeed = p.seed
        batchSize = p.count
        stopRequested = false
        btnCancel.text = if (p.count > 1) "Stop" else "Cancel"
        savePrefs()
        autoSaved = false
        setBusy(true, "Preparing…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-gen") {
            try {
                val n = inpaintCore(image, maskCopy, p) { k, f ->
                    // Show and save each image the moment it is finished.
                    ui.post {
                        if (k == 0) clearResults()
                        results += f
                        resultSeeds += p.seed + k
                        autoSaveIfHidden(listOf(f))
                        if (k == 0) showResultAt(0) else updateUi()
                    }
                }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    statusPrefix = ""
                    setBusy(false, "Done: $n image${if (n == 1) "" else "s"} in ${"%.0f".format(secs)}s")
                    if (results.isNotEmpty() && !canvas.showResult) showResultAt(resultIndex)
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                ui.post { statusPrefix = ""; setBusy(false, "Failed: ${t.message}"); pollNative(); gpuFailureHint() }
            } finally {
                maskCopy.recycle()
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    // ---------------------------------------------------------------- video (AnimateDiff trial)

    private fun motionFile(): File? =
        ModelStore.dir(this, "motion").listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") }

    private fun importMotion(uri: Uri) {
        if (busy) return
        val (name, _) = ModelStore.queryName(this, uri)
        setBusy(true, "Copying motion module $name…")
        thread(name = "motion-import") {
            try {
                val file = ModelStore.import(this, uri, "motion") { copied, total ->
                    ui.post { if (total > 0) txtStatus.text = "Copying motion module… ${ModelStore.gb(copied)} / ${ModelStore.gb(total)}" }
                }
                ModelStore.checkFile(file)?.let { file.delete(); throw IllegalStateException(it) }
                ui.post {
                    appendLog("Motion module: ${file.name}. Go to the Video tab and tap Load to use it.")
                    setBusy(false, "Motion module ready")
                }
            } catch (t: Throwable) {
                ui.post { setBusy(false, "Motion module failed: ${t.message}") }
            }
        }
    }

    /** Plays the clip in the preview while the Video tab is showing. */
    private val videoTicker = object : Runnable {
        override fun run() {
            if (mode != 3) return
            val frames = galResults[3]
            if (!busy && frames.size > 1) {
                galIndex[3] = (galIndex[3] + 1) % frames.size
                canvas.previewOriginal = null
                canvas.preview = frames[galIndex[3]]
            }
            ui.postDelayed(this, 1000L / videoFps)
        }
    }

    private fun createVideo() {
        if (busy) return
        if (loadedModelName == null) { toast("Load a model first"); return }
        if (motionFile() == null) { toast("Add a motion module first: Model → Choose motion module"); return }
        if (!loadedWithMotion) { toast("Tap Load (Model section) while on the Video tab to load the motion module"); return }
        val source = stylizeSource ?: run { toast("Tap Photo to choose a picture first"); return }
        val prompt = editPrompt.text.toString().trim()
        val negative = editNegative.text.toString().trim()
        val steps = (editSteps.text.toString().toIntOrNull() ?: 20).coerceIn(1, 150)
        val cfg = editCfg.text.toString().toFloatOrNull() ?: 7f
        val strength = (editStrength.text.toString().toFloatOrNull() ?: 0.75f).coerceIn(0.05f, 1f)
        var seed = editSeed.text.toString().toLongOrNull() ?: -1L
        if (seed < 0) seed = Random.nextLong(0, Int.MAX_VALUE.toLong())
        val sampler = (spinSampler.selectedItem as Choice).value
        val scheduler = (spinScheduler.selectedItem as Choice).value
        val size = (spinSize.selectedItem as Choice).value
        val clipSkip = editClipSkip.text.toString().toIntOrNull() ?: -1
        val frames = (findViewById<Spinner>(R.id.spinFrames).selectedItem as Choice).value
        prefs.edit().putInt("frames", findViewById<Spinner>(R.id.spinFrames).selectedItemPosition).apply()

        lastSeed = seed
        batchSize = 1
        stopRequested = false
        btnCancel.text = "Cancel"
        savePrefs()
        autoSaved = false
        setBusy(true, "Preparing video…")
        statusPrefix = "Video ($frames frames) · "
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-video") {
            try {
                val scale = size.toFloat() / maxOf(source.width, source.height)
                val w = maxOf(64, Math.round(source.width * scale / 64f) * 64)
                val h = maxOf(64, Math.round(source.height * scale / 64f) * 64)
                val scaled = Bitmap.createScaledBitmap(source, w, h, true)
                val rgb = ImageOps.rgbBytes(scaled)
                if (scaled !== source) scaled.recycle()
                ui.post {
                    appendLog("Video: $frames frames at ${w}×$h, denoise $strength, seed $seed\n  " + settingsLine() +
                        "\n  (the LoRA is not used for video)")
                }
                if (loadedBackend != "cpu") markGpuInFlight(true)
                val out = SdNative.video(rgb, w, h, prompt, negative, steps, cfg, strength, seed, sampler, scheduler,
                    clipSkip, frames, videoFps)
                markGpuInFlight(false)
                val frame = w * h * 3
                val n = out.size / frame
                val imgs = (0 until n).map { ImageOps.fromRgb(out, w, h, it * frame) }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    statusPrefix = ""
                    galResults[3].forEach { it.recycle() }
                    galResults[3].clear(); galSeeds[3].clear(); galIndex[3] = 0
                    galResults[3] += imgs
                    galSeeds[3] += List(n) { seed }
                    videoBytes = out; videoW = w; videoH = h
                    setBusy(false, "Video done: $n frames in ${"%.0f".format(secs)}s (${"%.0f".format(secs / 60)} min)")
                    appendLog("Video finished: $n frames in ${"%.0f".format(secs)}s")
                    if (!visible || findViewById<CheckBox>(R.id.chkAutoSave).isChecked) saveVideoGif()
                    if (mode == 3) { canvas.preview = imgs.firstOrNull(); updateUi() }
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                statusPrefix = ""
                ui.post { setBusy(false, "Video failed: ${t.message}"); pollNative(); gpuFailureHint() }
            } finally {
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    /** Saves the last clip to Pictures/StableAndroid as a looping GIF. */
    private fun saveVideoGif() {
        val bytes = videoBytes ?: return
        val w = videoW; val h = videoH
        thread(name = "gif-save") {
            val tmp = File(filesDir, "clip.gif")
            try {
                if (!SdNative.writeGif(tmp.absolutePath, bytes, w, h, videoFps)) throw IllegalStateException("couldn't write the GIF")
                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "video_${System.currentTimeMillis()}.gif")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StableAndroid")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ui.post { autoSaved = true; appendLog("Saved the clip as a GIF in Pictures/StableAndroid"); updateUi() }
            } catch (t: Throwable) {
                ui.post { appendLog("E Saving the GIF failed: ${t.message}"); toast("Saving the GIF failed: ${t.message}") }
            } finally {
                tmp.delete()
            }
        }
    }

    // ---------------------------------------------------------------- restore-original brush

    /** A scaled copy of the source photo made for an Img2Img restore; recycled when it ends. */
    private var restoreOrigTemp: Bitmap? = null

    /** True (with a hint) while the restore brush is active, so other actions wait for Done. */
    private fun restoringBlocks(): Boolean {
        if (!canvas.restoring) return false
        toast("Tap Done restoring first")
        return true
    }

    private fun toggleRestore() {
        if (busy) return
        if (canvas.restoring) { finishRestore(); return }
        val hasCurrent = (mode == 0 && results.isNotEmpty()) ||
            (mode == 1 && createResults.isNotEmpty() && stylizeSource != null)
        val options = mutableListOf<String>()
        if (hasCurrent) options += "The result on screen"
        options += "An image I saved earlier…"
        android.app.AlertDialog.Builder(this)
            .setTitle("Restore the original into…")
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("The result")) restoreCurrent()
                else {
                    toast("First choose the edited image")
                    pickImage(REQ_RESTORE_EDITED)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreCurrent() {
        when (mode) {
            0 -> {
                val base = results.getOrNull(resultIndex) ?: return
                val orig = canvas.image ?: return
                if (!canvas.startRestore(base, orig)) { toast("The result and the photo are different sizes"); return }
                restoreStarted()
            }
            1 -> {
                val res = createResults.getOrNull(createIndex) ?: return
                val src = stylizeSource ?: return
                beginRestoreWith(res.copy(Bitmap.Config.ARGB_8888, false), src.copy(Bitmap.Config.ARGB_8888, false),
                    createSeeds.getOrNull(createIndex) ?: -1L)
            }
        }
    }

    private var pendingRestoreEdited: Uri? = null

    /** Second step of "an image I saved earlier": both pictures are chosen, load and line them up. */
    private fun restoreFromFiles(edited: Uri, original: Uri) {
        setBusy(true, "Opening the two images…")
        thread {
            try {
                val e = ImageOps.load(contentResolver, edited)
                val o = ImageOps.load(contentResolver, original)
                // The two must be the same picture: the original is fitted exactly over the edit,
                // with no moving or resizing, so their shapes have to match.
                val ra = e.width.toFloat() / e.height
                val rb = o.width.toFloat() / o.height
                if (kotlin.math.abs(ra - rb) / ra > 0.03f) {
                    e.recycle(); o.recycle()
                    throw IllegalStateException("those two images are different shapes, so they aren't an edit and its " +
                        "original (${e.width}×${e.height} and ${o.width}×${o.height})")
                }
                ui.post {
                    setBusy(false, "Images opened")
                    beginRestoreWith(e, o, -1L)
                }
            } catch (t: Throwable) {
                ui.post { setBusy(false, "Couldn't start restoring: ${t.message}") }
            }
        }
    }

    /**
     * Starts the restore brush in the Inpaint tab with [edited] as the picture and [original]
     * fitted over it. Works at the larger of the two sizes, so restored parts keep the
     * original's full detail. Takes ownership of both bitmaps.
     */
    private fun beginRestoreWith(edited: Bitmap, original: Bitmap, seed: Long) {
        val useOrigSize = original.width.toLong() * original.height > edited.width.toLong() * edited.height
        val w = if (useOrigSize) original.width else edited.width
        val h = if (useOrigSize) original.height else edited.height
        fun fit(b: Bitmap): Bitmap {
            if (b.width == w && b.height == h) {
                if (b.isMutable) return b
                val m = b.copy(Bitmap.Config.ARGB_8888, true)
                b.recycle()
                return m
            }
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            if (s !== b) b.recycle()
            return s
        }
        val base = fit(edited)
        val orig = fit(original)
        if (mode != 0) switchMode(0)
        if (mode != 0) { base.recycle(); orig.recycle(); return }
        clearResults()
        canvas.setImage(base)
        results += base.copy(Bitmap.Config.ARGB_8888, false)
        resultSeeds += seed
        resultIndex = 0
        restoreOrigTemp = orig
        if (!canvas.startRestore(base, orig)) { orig.recycle(); restoreOrigTemp = null; toast("Couldn't line the two images up"); return }
        restoreStarted()
    }

    private fun restoreStarted() {
        btnBrush.text = "Brush"
        appendLog("Restore: started (${canvas.image?.width}×${canvas.image?.height})")
        txtStatus.text = "Paint where the original should come back. Two fingers to zoom. Tap Done restoring when finished."
        updateUi()
    }

    private fun finishRestore() {
        val out = canvas.finishRestore()
        restoreOrigTemp?.recycle(); restoreOrigTemp = null
        btnBrush.text = "Brush"
        if (out == null) {
            txtStatus.text = "Nothing was restored (no painted area was found)"
            appendLog("Restore: finished with nothing painted")
            if (results.isNotEmpty()) showResultAt(resultIndex) else updateUi()
            return
        }
        val i = resultIndex.coerceIn(0, maxOf(0, results.size - 1))
        if (results.isEmpty()) { results += out; resultSeeds += -1L } else {
            val old = results[i]
            results[i] = out
            canvas.result = out
            old.recycle()
        }
        autoSaved = false
        txtStatus.text = "Original restored over about ${canvas.lastRestoredPercent}% of the picture"
        appendLog("Restore: original painted back over about ${canvas.lastRestoredPercent}% of a ${out.width}×${out.height} image")
        showResultAt(i)
        if (findViewById<CheckBox>(R.id.chkAutoSave).isChecked) {
            val copy = out.copy(Bitmap.Config.ARGB_8888, false)
            thread {
                val ok = try { writePng(copy); true } catch (_: Throwable) { false }
                copy.recycle()
                ui.post {
                    if (ok) { autoSaved = true; appendLog("Saved the restored image to Pictures/StableAndroid"); updateUi() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- inpaint queue

    private class QueueItem(val id: Int, val name: String, val params: InpaintParams)
    private val queue = mutableListOf<QueueItem>()
    private var queueNextId = 1
    @Volatile private var queueRunning = false
    @Volatile private var queueStop = false

    private fun queueDir() = File(filesDir, "queue").apply { mkdirs() }

    /** Stores the open photo, its mask and the current settings as one queued job. */
    private fun addToQueue() {
        if (busy || restoringBlocks()) return
        val image = canvas.image ?: run { toast("Open an image first"); return }
        val mask = canvas.mask ?: return
        if (!canvas.hasMask()) { toast("Paint a mask over the area to change first"); return }
        val p = readInpaintParams()
        val id = queueNextId++
        val imgCopy = image.copy(Bitmap.Config.ARGB_8888, false)
        val maskCopy = mask.copy(Bitmap.Config.ALPHA_8, false)
        setBusy(true, "Adding to queue…")
        thread(name = "queue-add") {
            try {
                File(queueDir(), "$id.img.png").outputStream().use { imgCopy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // ALPHA_8 can't be written directly; store the alpha in an ARGB image.
                val px = ImageOps.alphaOf(maskCopy)
                val argb = Bitmap.createBitmap(IntArray(px.size) { (px[it].toInt() and 0xFF) shl 24 },
                    maskCopy.width, maskCopy.height, Bitmap.Config.ARGB_8888)
                File(queueDir(), "$id.mask.png").outputStream().use { argb.compress(Bitmap.CompressFormat.PNG, 100, it) }
                argb.recycle()
                ui.post {
                    queue += QueueItem(id, "Photo $id (${imgCopy.width}×${imgCopy.height})", p)
                    canvas.clearMask()
                    setBusy(false, "Added. ${queue.size} in the queue. Open the next photo, or tap Run queue.")
                }
            } catch (t: Throwable) {
                ui.post { setBusy(false, "Couldn't add to the queue: ${t.message}") }
            } finally {
                imgCopy.recycle(); maskCopy.recycle()
            }
        }
    }

    private fun clearQueue() {
        if (busy) return
        queue.clear()
        queueDir().listFiles()?.forEach { it.delete() }
        txtStatus.text = "Queue cleared"
        updateUi()
    }

    private fun runQueue() {
        if (busy || restoringBlocks()) return
        if (queue.isEmpty()) { toast("The queue is empty. Paint a mask and tap Add to queue."); return }
        if (loadedModelName == null) { toast("Load a model first"); return }
        if (!skipSettingsCheck && warnOnSettingsMismatch { runQueue() }) return
        skipSettingsCheck = false
        val items = queue.toList()
        queueRunning = true
        queueStop = false
        batchSize = 1
        stopRequested = false
        btnCancel.text = "Stop"
        setBusy(true, "Starting the queue…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()
        thread(name = "sd-queue") {
            var done = 0
            var saved = 0
            var failed = 0
            for ((i, item) in items.withIndex()) {
                if (queueStop) break
                statusPrefix = "Photo ${i + 1}/${items.size} · "
                ui.post { appendLog("Queue: ${item.name}, ${i + 1} of ${items.size}") }
                var image: Bitmap? = null
                var mask: Bitmap? = null
                try {
                    val imgFile = File(queueDir(), "${item.id}.img.png")
                    val maskFile = File(queueDir(), "${item.id}.mask.png")
                    image = android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath)
                        ?: throw IllegalStateException("queued photo is missing")
                    val maskArgb = android.graphics.BitmapFactory.decodeFile(maskFile.absolutePath)
                        ?: throw IllegalStateException("queued mask is missing")
                    mask = maskArgb.extractAlpha()
                    maskArgb.recycle()
                    inpaintCore(image, mask, item.params, "Photo ${i + 1}/${items.size} · ") { _, f ->
                        try { writePng(f); saved++ } catch (_: Throwable) { }
                        f.recycle()
                    }
                    done++
                    imgFile.delete(); maskFile.delete()
                    ui.post { queue.removeAll { it.id == item.id }; updateUi() }
                } catch (t: Throwable) {
                    markGpuInFlight(false)
                    if (queueStop) break          // cancelled by the user: leave it queued
                    failed++
                    ui.post { appendLog("E Queue: ${item.name} failed: ${t.message}. Moving on.") }
                } finally {
                    image?.recycle(); mask?.recycle()
                }
            }
            statusPrefix = ""
            queueRunning = false
            val secs = (System.currentTimeMillis() - started) / 1000
            ui.post {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val msg = "Queue ${if (queueStop) "stopped" else "finished"}: $done photo${if (done == 1) "" else "s"} done, " +
                    "$saved image${if (saved == 1) "" else "s"} saved" + (if (failed > 0) ", $failed failed" else "") +
                    (if (queue.isNotEmpty()) ", ${queue.size} still queued" else "") + " (${secs}s)"
                appendLog(msg)
                setBusy(false, msg)
            }
        }
    }

    private fun clearResults() {
        canvas.result = null
        canvas.showResult = false
        results.forEach { it.recycle() }
        results.clear(); resultSeeds.clear(); resultIndex = 0
    }

    private fun currentIndex() = if (mode != 0) createIndex else resultIndex

    private fun currentResult(): Bitmap? =
        if (mode != 0) createResults.getOrNull(createIndex) else canvas.result

    private fun showResultAt(i: Int) {
        if (canvas.restoring) return
        if (mode != 0) {
            if (createResults.isEmpty()) return
            createIndex = ((i % createResults.size) + createResults.size) % createResults.size
            refreshStylizePreview()
            updateUi()
            return
        }
        if (results.isEmpty()) return
        resultIndex = ((i % results.size) + results.size) % results.size
        canvas.result = results[resultIndex]
        canvas.showResult = true
        updateUi()
    }

    private fun promptKey(m: Int) = when (m) { 0 -> "prompt"; 1 -> "prompt_create"; 2 -> "prompt_text"; else -> "prompt_video" }
    private fun negativeKey(m: Int) = when (m) { 0 -> "negative"; 1 -> "negative_create"; 2 -> "negative_text"; else -> "negative_video" }

    private fun strengthKey(m: Int) = when (m) { 0 -> "strength"; 3 -> "strength_video"; else -> "strength_stylize" }

    private fun loadPromptsFor(m: Int) {
        editStrength.setText(prefs.getString(strengthKey(m), when (m) { 0 -> "1.0"; 3 -> "0.75"; else -> "0.6" }))
        if (m == 3) {
            editPrompt.setText(prefs.getString(promptKey(3), ""))
            editNegative.setText(prefs.getString(negativeKey(3), "blurry, low quality, deformed, static, watermark, text"))
        } else if (m == 0) {
            editPrompt.setText(prefs.getString(promptKey(0), ""))
            editNegative.setText(prefs.getString(negativeKey(0), "blurry, low quality, deformed, watermark, text"))
        } else if (m == 2) {
            editPrompt.setText(prefs.getString(promptKey(2), ""))
            editNegative.setText(prefs.getString(negativeKey(2),
                "blurry, low quality, deformed, bad anatomy, extra fingers, watermark, text"))
        } else {
            editPrompt.setText(prefs.getString(promptKey(1), ""))
            editNegative.setText(prefs.getString(negativeKey(1),
                "photo, realistic, blurry, low quality, deformed, bad anatomy, extra fingers, watermark, text"))
        }
    }

    private fun switchMode(m: Int) {
        if (busy || m == mode) return
        if (restoringBlocks()) return
        savePrefs()          // stores the current tab's prompts
        mode = m
        loadPromptsFor(m)
        savePrefs()
        applyMode()
    }

    private fun applyMode() {
        val create = mode != 0
        val text = mode == 2
        canvas.previewMode = create
        if (create) refreshStylizePreview() else { canvas.preview = null; canvas.previewOriginal = null }
        val inpaintVis = if (create) View.GONE else View.VISIBLE
        findViewById<View>(R.id.rowTools).visibility = inpaintVis
        findViewById<View>(R.id.rowBrush).visibility = inpaintVis
        findViewById<View>(R.id.cellArea).visibility = inpaintVis
        findViewById<View>(R.id.cellWorkSize).visibility = if (text) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cellStrength).visibility = if (text) View.GONE else View.VISIBLE
        findViewById<View>(R.id.cellTxtSize).visibility = if (text) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnStylePhoto).visibility = if (text) View.GONE else View.VISIBLE
        chkBlend.visibility = inpaintVis
        findViewById<View>(R.id.rowFeather).visibility = inpaintVis
        findViewById<View>(R.id.chkColorMatch).visibility = inpaintVis
        findViewById<View>(R.id.rowCreate).visibility = if (create) View.VISIBLE else View.GONE
        findViewById<View>(R.id.chkSharpen).visibility = if (create && mode != 3) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowVideo).visibility = if (mode == 3) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowQueue).visibility = inpaintVis
        btnGenerate.text = when (mode) { 0 -> "Inpaint"; 1 -> "Generate"; 2 -> "Create"; else -> "Make video" }
        findViewById<View>(R.id.btnTabVideo).alpha = if (mode == 3) 1f else 0.5f
        if (mode == 3) {
            if (!prefs.getBoolean("videoTuned", false)) {
                // AnimateDiff needs normal (non-LCM) sampling; start small so the first try is quick.
                prefs.edit().putBoolean("videoTuned", true).apply()
                spinSampler.setSelection(samplers.indexOfFirst { it.label == "Euler" })
                spinScheduler.setSelection(schedulers.indexOfFirst { it.label == "Karras" })
                editSteps.setText("20"); editCfg.setText("7"); editStrength.setText("0.75")
                spinSize.setSelection(sizes.indexOfFirst { it.value == 256 })
                appendLog("Video tab: set Euler, Karras, 20 steps, CFG 7, denoise 0.75, working size 256. " +
                    "These sampler settings are shared with the other tabs, so switch back (or use a preset) afterwards.")
            }
            ui.removeCallbacks(videoTicker)
            ui.post(videoTicker)
        }
        btnUseResult.text = if (create) "Edit in Inpaint" else "Keep & continue"
        editPrompt.hint = when (mode) {
            0 -> "what should appear in the masked area"
            1 -> "what the photo should become, e.g. a man with a beard in a red jacket, oil painting"
            else -> "describe the image, e.g. a lighthouse on a cliff at sunset, dramatic clouds"
        }
        btnTabInpaint.alpha = if (mode == 0) 1f else 0.5f
        btnTabCreate.alpha = if (mode == 1) 1f else 0.5f
        btnTabText.alpha = if (mode == 2) 1f else 0.5f
        updateUi()
    }

    /** Stylize tab: redraw the whole photo in the chosen style (img2img, no mask). */
    private fun create() {
        if (busy) return
        if (loadedModelName == null) { toast("Load a model first"); return }
        if (!skipSettingsCheck && warnOnSettingsMismatch { create() }) return
        skipSettingsCheck = false
        val textMode = mode == 2
        val source = if (textMode) null else (stylizeSource ?: run { toast("Tap Photo to choose a picture first"); return })
        val style = spinStyle.selectedItem as Style
        val userPrompt = editPrompt.text.toString().trim()
        if (userPrompt.isEmpty() && (textMode || style.tags.isEmpty())) {
            toast(if (textMode) "Describe the image first" else "Pick a style or describe the result"); return
        }
        val (txtW, txtH) = txtDims[(spinTxtSize.selectedItem as Choice).value]
        val sharpen = findViewById<CheckBox>(R.id.chkSharpen).isChecked
        val prompt = listOf(style.tags, userPrompt).filter { it.isNotEmpty() }.joinToString(", ")
        val negative = listOf(editNegative.text.toString().trim(), style.negative)
            .filter { it.isNotEmpty() }.joinToString(", ")
        val steps = (editSteps.text.toString().toIntOrNull() ?: 20).coerceIn(1, 150)
        val cfg = editCfg.text.toString().toFloatOrNull() ?: 7f
        val strength = (editStrength.text.toString().toFloatOrNull() ?: 0.6f).coerceIn(0.05f, 1f)
        var seed = editSeed.text.toString().toLongOrNull() ?: -1L
        if (seed < 0) seed = Random.nextLong(0, Int.MAX_VALUE.toLong())
        val sampler = (spinSampler.selectedItem as Choice).value
        val scheduler = (spinScheduler.selectedItem as Choice).value
        val size = (spinSize.selectedItem as Choice).value
        val clipSkip = editClipSkip.text.toString().toIntOrNull() ?: -1
        val tiling = chkTiling.isChecked && !loadedWithTae
        val count = (editImages.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8)

        lastSeed = seed
        batchSize = count
        stopRequested = false
        btnCancel.text = if (count > 1) "Stop" else "Cancel"
        savePrefs()
        autoSaved = false
        setBusy(true, "Preparing…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-stylize") {
            try {
                // Whole photo scaled so its long side is about the working size (multiples of 64).
                var w = txtW
                var h = txtH
                var rgb: ByteArray? = null
                var fullMask: ByteArray? = null
                if (source != null) {
                    val scale = size.toFloat() / maxOf(source.width, source.height)
                    w = maxOf(64, Math.round(source.width * scale / 64f) * 64)
                    h = maxOf(64, Math.round(source.height * scale / 64f) * 64)
                    val scaled = Bitmap.createScaledBitmap(source, w, h, true)
                    rgb = ImageOps.rgbBytes(scaled)
                    if (scaled !== source) scaled.recycle()
                    fullMask = ByteArray(w * h) { 255.toByte() }
                } else if (loadedIsInpaint) {
                    // Inpainting models can't run from text alone: give them a blank grey
                    // canvas with everything masked, which is the same as repainting it all.
                    rgb = ByteArray(w * h * 3) { 127 }
                    fullMask = ByteArray(w * h) { 255.toByte() }
                    ui.post { appendLog("Inpainting model in the Text tab: using a blank canvas. A normal SD 1.5 model gives better results here.") }
                }
                ui.post {
                    appendLog((if (textMode) "Text to image" else "Img2Img (denoise $strength)") +
                        " $count× ${w}×$h, style ${style.label}, seeds $seed–${seed + count - 1}\n  " + settingsLine())
                }
                var made = 0
                for (k in 0 until count) {
                    if (k > 0 && stopRequested) break
                    statusPrefix = if (count > 1) "Image ${k + 1}/$count · " else ""
                    if (loadedBackend != "cpu") markGpuInFlight(true)
                    val out = try {
                        SdNative.inpaint(rgb, fullMask, w, h, prompt, negative, steps, cfg,
                            if (textMode) 1f else strength, seed + k,
                            sampler, scheduler, clipSkip, tiling, 1, loraFile?.absolutePath, loraStrength())
                    } catch (t: Throwable) {
                        markGpuInFlight(false)
                        if (made > 0 && stopRequested) break   // cancelled after some were finished: keep those
                        throw t
                    }
                    markGpuInFlight(false)
                    var img = ImageOps.fromRgb(out, w, h)
                    if (sharpen && !stopRequested) {
                        val better = sharpenFaces(img, k + 1, count, prompt, negative, steps, cfg, seed + k,
                            sampler, scheduler, clipSkip, tiling)
                        if (better !== img) img.recycle()
                        img = better
                    }
                    val finished = img
                    val first = made == 0
                    // Show and save each image the moment it is finished.
                    ui.post {
                        if (first) clearCreateResults()
                        createResults += finished
                        createSeeds += seed + k
                        autoSaveIfHidden(listOf(finished))
                        if (first) showResultAt(0) else updateUi()
                    }
                    made++
                }
                statusPrefix = ""
                val n = made
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    setBusy(false, "Done: $n image${if (n == 1) "" else "s"} in ${"%.0f".format(secs)}s")
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                ui.post { statusPrefix = ""; setBusy(false, "Failed: ${t.message}"); pollNative(); gpuFailureHint() }
            } finally {
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    /** True when the results on screen were already written by auto-save. */
    private var autoSaved = false

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("askedNotif", false)) return
        prefs.edit().putBoolean("askedNotif", true).apply()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    /** If the app isn't on screen when a run finishes, save the results so they can't be lost. */
    private fun autoSaveIfHidden(images: List<Bitmap>) {
        val always = findViewById<CheckBox>(R.id.chkAutoSave).isChecked
        if ((visible && !always) || images.isEmpty()) return
        val copies = images.map { it.copy(Bitmap.Config.ARGB_8888, false) }
        thread {
            var saved = 0
            var error: String? = null
            copies.forEach {
                try { writePng(it); saved++ } catch (t: Throwable) { error = t.message }
                it.recycle()
            }
            ui.post {
                appendLog(if (error == null) "Saved $saved image(s) to Pictures/StableAndroid"
                    else "E Auto-save: $saved of ${copies.size} saved (${error})")
                autoSaved = error == null
                updateUi()
            }
        }
    }

    private fun writePng(bmp: Bitmap) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sd_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StableAndroid")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    @Volatile private var statusPrefix = ""

    /**
     * Enlarges [img] ×2, finds faces with Android's built-in detector and redraws each one at
     * full working size, so eyes and features get far more pixels. Runs on the worker thread.
     * Returns the enlarged image (with or without redrawn faces); never throws.
     */
    private fun sharpenFaces(img: Bitmap, index: Int, total: Int, prompt: String, negative: String,
                             steps: Int, cfg: Float, seed: Long, sampler: Int, scheduler: Int,
                             clipSkip: Int, tiling: Boolean): Bitmap {
        val bw = (img.width * 2) and 1.inv()
        val bh = img.height * 2
        var big = Bitmap.createScaledBitmap(img, bw, bh, true)
        try {
            val probe = big.copy(Bitmap.Config.RGB_565, false)
            val found = arrayOfNulls<android.media.FaceDetector.Face>(4)
            val count = android.media.FaceDetector(bw, bh, 4).findFaces(probe, found)
            probe.recycle()
            val faces = found.take(count).filterNotNull().filter { it.confidence() >= 0.3f }
            if (faces.isEmpty()) {
                ui.post { appendLog("Sharpen faces: no face found in image $index, so it was only enlarged.") }
                return big
            }
            faces.forEachIndexed { f, face ->
                val mid = android.graphics.PointF().also { face.getMidPoint(it) }
                val eyes = face.eyesDistance()
                val mask = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
                android.graphics.Canvas(mask).drawOval(
                    mid.x - eyes * 1.5f, mid.y - eyes * 1.6f, mid.x + eyes * 1.5f, mid.y + eyes * 2.2f,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
                val job = ImageOps.prepare(big, mask, true, 512, 32)
                if (job != null) {
                    statusPrefix = "Sharpening face ${f + 1}/${faces.size}" +
                        (if (total > 1) " (image $index/$total)" else "") + " · "
                    ui.post { appendLog("Sharpen faces: image $index face ${f + 1}, region ${job.region.toShortString()} at ${job.width}×${job.height}") }
                    if (loadedBackend != "cpu") markGpuInFlight(true)
                    val out = SdNative.inpaint(job.rgb, job.mask, job.width, job.height,
                        "$prompt, detailed face, sharp detailed eyes", negative, maxOf(steps, 8), cfg, 0.45f, seed,
                        sampler, scheduler, clipSkip, tiling, 1, loraFile?.absolutePath, loraStrength())
                    markGpuInFlight(false)
                    val gen = ImageOps.fromRgb(out, job.width, job.height)
                    val next = ImageOps.composite(big, mask, job, gen, true)
                    gen.recycle()
                    big.recycle()
                    big = next
                }
                mask.recycle()
            }
        } catch (t: Throwable) {
            markGpuInFlight(false)
            ui.post { appendLog("Sharpen faces stopped: ${t.message}. Keeping the enlarged image.") }
        }
        return big
    }

    private fun saveResult() {
        if (restoringBlocks()) return
        if (mode == 3) { saveVideoGif(); return }
        val bmp = currentResult() ?: return
        thread {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "inpaint_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StableAndroid")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                ui.post { toast("Saved to Pictures/StableAndroid") }
            } catch (t: Throwable) {
                ui.post { toast("Save failed: ${t.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- ui state

    private fun setBusy(b: Boolean, status: String) {
        val wasBusy = busy
        busy = b
        txtStatus.text = status
        if (b) {
            askNotificationPermissionOnce()
            WorkService.start(this, status)
        } else if (wasBusy) {
            WorkService.finish(this, status, announce = !visible)
        }
        progress.isIndeterminate = b
        if (!b) {
            progress.isIndeterminate = false
            progress.progress = 0
            pollNative()
        } else {
            ui.removeCallbacks(poller)
            ui.post(poller)
        }
        updateUi()
    }

    private fun pollNative() {
        val log = try { SdNative.drainLog() } catch (t: Throwable) { "" }
        if (log.isNotEmpty()) appendLog(log.trimEnd())
        if (!busy) return
        val p = SdNative.progress()
        val step = p[0]; val total = p[1]; val img = p[2]
        val stage = SdNative.stage()
        val imgLabel = if (statusPrefix.isNotEmpty()) statusPrefix
            else if (batchSize > 1 && img > 0) "Image ${img.coerceAtMost(batchSize)}/$batchSize · " else ""
        if (step > 0 && total > 0) {
            progress.isIndeterminate = false
            progress.max = total
            progress.progress = step
            val t = SdNative.lastStepTime()
            val speed = if (t > 0) " · ${"%.1f".format(t)}s/it" else ""
            txtStatus.text = imgLabel + "Step $step/$total$speed" + if (stage.isNotEmpty()) " · $stage" else ""
            WorkService.update(this, imgLabel + "Step $step/$total$speed", step, total)
        } else if (stage.isNotEmpty() && !txtStatus.text.contains("Step ")) {
            txtStatus.text = stage
        }
    }

    private fun appendLog(s: String) {
        try { File(filesDir, "session.log").appendText(s + "\n") } catch (_: Throwable) { }
        logBuffer.append(s).append('\n')
        if (logBuffer.length > 30_000) logBuffer.delete(0, logBuffer.length - 30_000)
        txtLog.text = logBuffer.takeLast(6_000)
    }

    private fun updateUi() {
        val loaded = loadedModelName != null
        txtModel.text = when {
            loaded -> "Loaded: $loadedModelName"
            modelFile != null -> "Ready: ${modelFile!!.name} (${ModelStore.gb(modelFile!!.length())}, not loaded)"
            else -> "No .safetensors chosen. Use an SD 1.5 inpainting checkpoint for best results."
        }
        btnGenerate.isEnabled = !busy && loaded && when (mode) {
            0 -> canvas.image != null
            1, 3 -> stylizeSource != null
            else -> true
        }
        btnTabText.isEnabled = !busy
        findViewById<View>(R.id.btnTabVideo).isEnabled = !busy
        findViewById<Button>(R.id.btnQueueAdd).isEnabled = !busy && canvas.image != null
        findViewById<Button>(R.id.btnQueueRun).apply { isEnabled = !busy && loaded && queue.isNotEmpty(); text = "Run queue (${queue.size})" }
        findViewById<Button>(R.id.btnQueueClear).isEnabled = !busy && queue.isNotEmpty()
        findViewById<TextView>(R.id.txtMotion).text = motionFile()?.let {
            "Video motion module: ${it.name}" + if (loadedWithMotion) " (loaded)" else " (tap Load while on the Video tab)"
        } ?: "Video motion module: none"
        btnTabInpaint.isEnabled = !busy
        btnTabCreate.isEnabled = !busy
        if (mode == 1) canvas.previewHint = "Tap Photo to choose a picture to transform"
        if (mode == 2) canvas.previewHint = "Type a prompt and tap Create"
        btnCancel.isEnabled = busy && loaded
        btnLoadModel.isEnabled = !busy && modelFile != null
        findViewById<Button>(R.id.btnRestore).apply {
            visibility = if (mode == 0 || mode == 1) View.VISIBLE else View.GONE
            isEnabled = !busy
            text = if (canvas.restoring) "Done restoring" else "Restore original (paint it back)"
        }
        findViewById<TextView>(R.id.txtDescribe).text = Describer.missing(this).let { m ->
            when (m.size) {
                0 -> "Describe model: ready (Florence-2)"
                4 -> "Describe model: not installed (needs 4 Florence-2 .onnx files)"
                else -> "Describe model: still needs ${m.joinToString()}"
            }
        }
        findViewById<Button>(R.id.btnDescribe).text = if (mode == 2) "Describe an image" else "Describe image"
        findViewById<TextView>(R.id.txtDriver).text = GpuDriver.installed(this)?.let {
            "GPU driver: ${it.name}" + when {
                customDriverActive -> " (active)"
                !prefs.getBoolean("driverEnabled", true) -> " (turned off after a crash)"
                else -> " (restart the app to use it)"
            }
        } ?: ("GPU driver: system" + if (customDriverActive) " (restart the app to apply)" else "")
        txtLora.text = loraFile?.let { "LoRA: ${it.name}" } ?: "LoRA: none (optional add-on, e.g. an LCM LoRA for 4–8 step runs)"
        btnSave.isEnabled = !busy && currentResult() != null
        btnSave.text = if (autoSaved && currentResult() != null) "Saved ✓ (save again)" else "Save"
        btnUseResult.isEnabled = !busy && currentResult() != null
        btnToggle.isEnabled = canvas.result != null
        btnToggle.text = if (canvas.showResult) "Original" else "Result"
        if (mode != 0) {
            rowResults.visibility = if (createResults.isNotEmpty()) View.VISIBLE else View.GONE
            if (createResults.isNotEmpty()) {
                txtResult.text = "Result ${createIndex + 1}/${createResults.size} · seed ${createSeeds[createIndex]}" +
                    if (mode == 1) " · hold to compare" else ""
                if (mode == 3) txtResult.text = "Clip: ${createResults.size} frames · seed ${createSeeds.firstOrNull() ?: ""}"
            }
        } else rowResults.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
        if (mode == 0 && results.isNotEmpty()) {
            txtResult.text = "Result ${resultIndex + 1}/${results.size} · seed ${resultSeeds[resultIndex]}" +
                if (canvas.showResult) "" else " (showing original)"
        }
        if (!busy) btnCancel.text = "Cancel"
    }

    /** Shows why the app last exited (e.g. killed for low memory) and the tail of that session's log. */
    private fun showPreviousSession() {
        val cur = File(filesDir, "session.log")
        val prev = File(filesDir, "previous.log")
        if (cur.exists()) { prev.delete(); cur.renameTo(prev) }
        val lines = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(ActivityManager::class.java)
                am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let { info ->
                    val reason = when (info.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed by Android for low memory"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                        ApplicationExitInfo.REASON_CRASH -> "app crash"
                        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${info.status}"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by user"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
                        else -> "reason code ${info.reason}"
                    }
                    val pssMb = info.pss / 1024
                    val rssMb = info.rss / 1024
                    lines += "Last exit: $reason (memory at exit: PSS ${pssMb} MB, RSS ${rssMb} MB)" +
                        (info.description?.let { " – $it" } ?: "")
                }
            } catch (_: Throwable) { }
        }
        if (prev.exists()) {
            val tail = try { prev.readLines().takeLast(40) } catch (_: Throwable) { emptyList() }
            if (tail.isNotEmpty()) {
                lines += "--- end of previous session log ---"
                lines += tail
                lines += "--- current session ---"
            }
        }
        if (lines.isNotEmpty()) appendLog(lines.joinToString("\n"))
    }

    /**
     * ggml reads these when it first sets up the GPU, so they must be set before any
     * native call. "Safe mode" turns off matrix-core (cooperative matrix), integer-dot and
     * bfloat16 shaders, which Qualcomm's stock driver often fails to build.
     */
    /** Writes any uncaught error to the session log, so the next launch can show what happened. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val logFile = File(filesDir, "session.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = android.util.Log.getStackTraceString(error).lines().take(25).joinToString("\n")
                logFile.appendText("E APP CRASH on ${thread.name}:\n$trace\n")
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private var customDriverActive = false
    private var startupNotes = ""

    /** Opens the installed Turnip driver, if any. Must run before any other native call. */
    private fun applyGpuDriver() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        val info = GpuDriver.installed(this) ?: return
        if (!p.getBoolean("driverEnabled", true)) return
        if (p.getBoolean("gpuInFlight", false)) {
            // The app died while this driver was in use: don't load it again automatically.
            p.edit().putBoolean("driverEnabled", false).apply()
            startupNotes += "The custom GPU driver was in use when the app closed, so it has been turned off. " +
                "Tap 'Install Turnip (.zip)' to try another version.\n"
            return
        }
        customDriverActive = try {
            SdNative.setGpuDriver(applicationInfo.nativeLibraryDir, info.dir.absolutePath, info.library,
                cacheDir.absolutePath)
        } catch (t: Throwable) { false }
        startupNotes += if (customDriverActive) "GPU driver: ${info.name} (custom)\n"
            else "Custom GPU driver ${info.name} could not be opened; using the system driver.\n"
    }

    private fun applyGpuEnv() {
        // Clear anything a library may have queued in the cache folder by earlier builds.
        try { cacheDir.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Throwable) { }
        try { android.system.Os.setenv("ORT_DISABLE_TELEMETRY", "1", true) } catch (_: Throwable) { }
        applyGpuDriver()
        val safe = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("gpuSafe", true)
        val flags = listOf("GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2",
            "GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "GGML_VK_DISABLE_BFLOAT16",
            "GGML_VK_DISABLE_FUSION", "GGML_VK_DISABLE_MULTI_ADD", "GGML_VK_DISABLE_GRAPH_OPTIMIZE")
        // Half-precision GPU maths can overflow to NaN on some drivers (grey/black images).
        val f32 = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("gpuF32", false)
        try {
            if (f32) android.system.Os.setenv("GGML_VK_DISABLE_F16", "1", true)
            else android.system.Os.unsetenv("GGML_VK_DISABLE_F16")
        } catch (_: Throwable) { }
        try {
            for (f in flags) {
                if (safe) android.system.Os.setenv(f, "1", true) else android.system.Os.unsetenv(f)
            }
        } catch (_: Throwable) { }
    }

    /** Copies the bundled TAESD weights out of the APK once; null if this build has none. */
    private fun taesdFile(): File? {
        val out = File(filesDir, "taesd.safetensors")
        return try {
            assets.open("taesd.safetensors").use { input ->
                if (!out.exists() || out.length() != input.available().toLong()) {
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            out
        } catch (_: Throwable) { null }
    }

    private fun gpuFailureHint() {
        if (loadedBackend == "cpu") return
        appendLog("GPU run failed. Things to try, in order: tick 'GPU safe mode' and restart the app; " +
            "choose 'GPU + CPU for VAE'; or switch back to CPU. Please send this log.")
    }

    /** Set while the GPU is in use; if the app dies meanwhile, the next launch falls back to CPU. */
    private fun markGpuInFlight(on: Boolean) {
        prefs.edit().putBoolean("gpuInFlight", on).commit()
    }

    private fun checkGpuCrashMarker() {
        if (!prefs.getBoolean("gpuInFlight", false)) return
        markGpuInFlight(false)
        spinCompute.setSelection(0)
        prefs.edit().putInt("compute", 0).apply()
        appendLog("The app closed while using the GPU last time, so it has been switched back to CPU. " +
            "Copy this log and send it over; you can pick GPU again under Model → Run on.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_IMAGE = 1
        private const val REQ_MODEL = 2
        private const val REQ_STYLE_IMAGE = 3
        private const val REQ_LORA = 4
        private const val REQ_DRIVER = 5
        private const val REQ_DESCRIBE_FILES = 6
        private const val REQ_DESCRIBE_IMAGE = 7
        private const val REQ_MODEL_FOLDER = 8
        private const val REQ_MOTION = 9
        private const val REQ_RESTORE_EDITED = 10
        private const val REQ_RESTORE_ORIGINAL = 11
    }
}
