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
    private val sizes = listOf(Choice("384", 384), Choice("512", 512), Choice("640", 640), Choice("768", 768))
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
    private lateinit var spinStyle: Spinner
    private lateinit var btnTabInpaint: Button
    private lateinit var btnTabCreate: Button
    /** 0 = Inpaint tab, 1 = Stylize tab. */
    private var stylizeSource: Bitmap? = null
    private var mode = 0
    private val createResults = mutableListOf<Bitmap>()
    private val createSeeds = mutableListOf<Long>()
    private var createIndex = 0
    private var loadedBackend = "cpu"
    private lateinit var chkBlend: CheckBox
    private lateinit var chkFlash: CheckBox
    private lateinit var chkMmap: CheckBox
    private lateinit var chkTiling: CheckBox

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private var modelFile: File? = null
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
        applyGpuEnv()
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        setupSpinners()
        restorePrefs()
        setupListeners()
        showPreviousSession()
        checkGpuCrashMarker()

        thread {
            val info = (try { SdNative.systemInfo() } catch (t: Throwable) { "native library failed to load: $t" }) +
                "\nGPU safe mode: " + (if (prefs.getBoolean("gpuSafe", true)) "on" else "off")
            ui.post { appendLog(info) }
        }
        handleIncomingIntent(intent)
        applyMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
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
        spinStyle = findViewById(R.id.spinStyle)
        btnTabInpaint = findViewById(R.id.btnTabInpaint)
        btnTabCreate = findViewById(R.id.btnTabCreate)
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
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnImage).setOnClickListener { pickImage(REQ_IMAGE) }
        btnBrush.setOnClickListener {
            canvas.erasing = !canvas.erasing
            btnBrush.text = if (canvas.erasing) "Eraser" else "Brush"
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { canvas.undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { canvas.clearMask() }
        findViewById<Button>(R.id.btnInvert).setOnClickListener { canvas.invertMask() }
        findViewById<Button>(R.id.btnFit).setOnClickListener { canvas.resetView() }
        btnToggle.setOnClickListener {
            if (canvas.result != null) {
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
            // With several images, the first tap keeps the ones already finished.
            if (batchSize > 1 && !stopRequested) {
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
        findViewById<Button>(R.id.btnStylePhoto).setOnClickListener { pickImage(REQ_STYLE_IMAGE) }
        btnSave.setOnClickListener { saveResult() }
        btnUseResult.setOnClickListener {
            if (mode == 1) {
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
            val s = (if (mode == 1) createSeeds.getOrNull(createIndex) else resultSeeds.getOrNull(resultIndex)) ?: lastSeed
            if (s >= 0) editSeed.setText(s.toString())
        }
        findViewById<Button>(R.id.btnChooseModel).setOnClickListener { pickModel() }
        btnLoadModel.setOnClickListener { loadModel() }
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
        chkGpuSafe.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("gpuSafe", on).apply()
            toast("Close and reopen the app for this to take effect")
        }
        spinStyle.setSelection(prefs.getInt("style", 1))
        chkBlend.isChecked = prefs.getBoolean("blend", true)
        chkFlash.isChecked = prefs.getBoolean("flash", true)
        chkMmap.isChecked = prefs.getBoolean("mmap", false)
        chkTiling.isChecked = prefs.getBoolean("tiling", true)
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
            .putInt("mode", mode)
            .putBoolean("blend", chkBlend.isChecked)
            .putBoolean("flash", chkFlash.isChecked)
            .putBoolean("mmap", chkMmap.isChecked)
            .putBoolean("tiling", chkTiling.isChecked)
            .putString("modelPath", modelFile?.absolutePath)
            .apply()
    }

    // ---------------------------------------------------------------- pickers

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMAGE -> openImage(uri)
            REQ_STYLE_IMAGE -> openStylizeImage(uri)
            REQ_MODEL -> importModel(uri)
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

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_MODEL)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        uri?.let { if (mode == 1) openStylizeImage(it) else openImage(it) }
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
                    txtStatus.text = "Photo ${bmp.width}×${bmp.height}. Pick a style and tap Stylize."
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
        val res = createResults.getOrNull(createIndex)
        canvas.preview = res ?: stylizeSource
        canvas.previewOriginal = if (res != null) stylizeSource else null
    }

    private fun openImage(uri: Uri) {
        thread {
            try {
                val bmp = ImageOps.load(contentResolver, uri)
                ui.post {
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
                val file = ModelStore.import(this, uri) { copied, total ->
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

    private fun loadModel() {
        val file = modelFile ?: run { toast("Choose a model file first"); return }
        if (busy) return
        val threads = editThreads.text.toString().toIntOrNull() ?: 4
        val wtype = (spinWeights.selectedItem as Choice).value
        val flash = chkFlash.isChecked
        val mmap = chkMmap.isChecked
        val computeMode = (spinCompute.selectedItem as Choice).value
        val wantGpu = computeMode != 0
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
                        backend = if (computeMode == 2) "all=$dev,vae=cpu" else dev
                        gpuName = vk.substringAfter('\t', "")
                    } else {
                        ui.post { appendLog("No usable Vulkan GPU found. Using the CPU instead.") }
                    }
                }
                val version = SdNative.loadModel(file.absolutePath, null, threads, wtype, flash, mmap, backend)
                markGpuInFlight(false)
                loadedBackend = backend
                val secs = (System.currentTimeMillis() - started) / 1000
                ui.post {
                    loadedModelName = name
                    val where = if (backend == "cpu") "CPU" else "GPU ${gpuName.ifEmpty { backend }}"
                    setBusy(false, "Loaded $version on $where in ${secs}s")
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

    private fun generate() {
        if (mode == 1) { create(); return }
        if (busy) return
        if (loadedModelName == null) { toast("Load a model first"); return }
        val image = canvas.image ?: run { toast("Open an image first"); return }
        val mask = canvas.mask ?: return
        // If a result is showing, inpaint on top of the original again.
        canvas.showResult = false

        val prompt = editPrompt.text.toString()
        val negative = editNegative.text.toString()
        val steps = (editSteps.text.toString().toIntOrNull() ?: 20).coerceIn(1, 150)
        val cfg = editCfg.text.toString().toFloatOrNull() ?: 7f
        val strength = (editStrength.text.toString().toFloatOrNull() ?: 1f).coerceIn(0.01f, 1f)
        var seed = editSeed.text.toString().toLongOrNull() ?: -1L
        if (seed < 0) seed = Random.nextLong(0, Int.MAX_VALUE.toLong())
        val sampler = (spinSampler.selectedItem as Choice).value
        val scheduler = (spinScheduler.selectedItem as Choice).value
        val size = (spinSize.selectedItem as Choice).value
        val onlyMasked = (spinArea.selectedItem as Choice).value == 1
        val blend = chkBlend.isChecked
        val clipSkip = editClipSkip.text.toString().toIntOrNull() ?: -1
        val tiling = chkTiling.isChecked
        val count = (editImages.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8)
        val maskCopy = mask.copy(Bitmap.Config.ALPHA_8, false)

        lastSeed = seed
        batchSize = count
        stopRequested = false
        btnCancel.text = if (count > 1) "Stop" else "Cancel"
        savePrefs()
        setBusy(true, "Preparing…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-gen") {
            try {
                val job = ImageOps.prepare(image, maskCopy, onlyMasked, size, 32)
                    ?: throw IllegalStateException("Paint a mask over the area you want to change first")
                ui.post {
                    appendLog("Inpainting $count× ${job.width}×${job.height} from region " +
                        "${job.region.toShortString()}, seeds $seed–${seed + count - 1}")
                }
                if (loadedBackend != "cpu") markGpuInFlight(true)
                val out = SdNative.inpaint(job.rgb, job.mask, job.width, job.height, prompt, negative,
                    steps, cfg, strength, seed, sampler, scheduler, clipSkip, tiling, count)
                markGpuInFlight(false)
                val frame = job.width * job.height * 3
                val n = out.size / frame
                val finals = (0 until n).map { k ->
                    val gen = ImageOps.fromRgb(out, job.width, job.height, k * frame)
                    val f = ImageOps.composite(image, maskCopy, job, gen, blend)
                    gen.recycle()
                    f
                }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    clearResults()
                    results += finals
                    resultSeeds += (0 until n).map { seed + it }
                    setBusy(false, "Done: $n image${if (n == 1) "" else "s"} in ${"%.0f".format(secs)}s")
                    showResultAt(0)
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                ui.post { setBusy(false, "Failed: ${t.message}"); pollNative(); gpuFailureHint() }
            } finally {
                maskCopy.recycle()
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun clearResults() {
        canvas.result = null
        canvas.showResult = false
        results.forEach { it.recycle() }
        results.clear(); resultSeeds.clear(); resultIndex = 0
    }

    private fun currentIndex() = if (mode == 1) createIndex else resultIndex

    private fun currentResult(): Bitmap? =
        if (mode == 1) createResults.getOrNull(createIndex) else canvas.result

    private fun showResultAt(i: Int) {
        if (mode == 1) {
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

    private fun promptKey(m: Int) = if (m == 0) "prompt" else "prompt_create"
    private fun negativeKey(m: Int) = if (m == 0) "negative" else "negative_create"

    private fun strengthKey(m: Int) = if (m == 0) "strength" else "strength_stylize"

    private fun loadPromptsFor(m: Int) {
        editStrength.setText(prefs.getString(strengthKey(m), if (m == 0) "1.0" else "0.6"))
        if (m == 0) {
            editPrompt.setText(prefs.getString(promptKey(0), ""))
            editNegative.setText(prefs.getString(negativeKey(0), "blurry, low quality, deformed, watermark, text"))
        } else {
            editPrompt.setText(prefs.getString(promptKey(1), ""))
            editNegative.setText(prefs.getString(negativeKey(1),
                "photo, realistic, blurry, low quality, deformed, bad anatomy, extra fingers, watermark, text"))
        }
    }

    private fun switchMode(m: Int) {
        if (busy || m == mode) return
        savePrefs()          // stores the current tab's prompts
        mode = m
        loadPromptsFor(m)
        savePrefs()
        applyMode()
    }

    private fun applyMode() {
        val create = mode == 1
        canvas.previewMode = create
        if (create) refreshStylizePreview() else { canvas.preview = null; canvas.previewOriginal = null }
        val inpaintVis = if (create) View.GONE else View.VISIBLE
        findViewById<View>(R.id.rowTools).visibility = inpaintVis
        findViewById<View>(R.id.rowBrush).visibility = inpaintVis
        findViewById<View>(R.id.cellArea).visibility = inpaintVis
        chkBlend.visibility = inpaintVis
        findViewById<View>(R.id.rowCreate).visibility = if (create) View.VISIBLE else View.GONE
        btnGenerate.text = if (create) "Stylize" else "Inpaint"
        btnUseResult.text = if (create) "Edit in Inpaint" else "Keep & continue"
        editPrompt.hint = if (create) "optional: describe the photo, e.g. a man with a beard in a red jacket"
            else "what should appear in the masked area"
        btnTabInpaint.alpha = if (create) 0.5f else 1f
        btnTabCreate.alpha = if (create) 1f else 0.5f
        updateUi()
    }

    /** Stylize tab: redraw the whole photo in the chosen style (img2img, no mask). */
    private fun create() {
        if (busy) return
        if (loadedModelName == null) { toast("Load a model first"); return }
        val source = stylizeSource ?: run { toast("Tap Photo to choose a picture first"); return }
        val style = spinStyle.selectedItem as Style
        val userPrompt = editPrompt.text.toString().trim()
        if (userPrompt.isEmpty() && style.tags.isEmpty()) { toast("Pick a style or describe the result"); return }
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
        val tiling = chkTiling.isChecked
        val count = (editImages.text.toString().toIntOrNull() ?: 1).coerceIn(1, 8)

        lastSeed = seed
        batchSize = count
        stopRequested = false
        btnCancel.text = if (count > 1) "Stop" else "Cancel"
        savePrefs()
        setBusy(true, "Preparing…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-stylize") {
            try {
                // Whole photo scaled so its long side is about the working size (multiples of 64).
                val scale = size.toFloat() / maxOf(source.width, source.height)
                val w = maxOf(64, Math.round(source.width * scale / 64f) * 64)
                val h = maxOf(64, Math.round(source.height * scale / 64f) * 64)
                val scaled = Bitmap.createScaledBitmap(source, w, h, true)
                val rgb = ImageOps.rgbBytes(scaled)
                if (scaled !== source) scaled.recycle()
                val fullMask = ByteArray(w * h) { 255.toByte() }
                ui.post { appendLog("Stylizing $count× ${w}×$h (${style.label}, denoise $strength), seeds $seed–${seed + count - 1}") }
                if (loadedBackend != "cpu") markGpuInFlight(true)
                val out = SdNative.inpaint(rgb, fullMask, w, h, prompt, negative, steps, cfg, strength, seed,
                    sampler, scheduler, clipSkip, tiling, count)
                markGpuInFlight(false)
                val frame = w * h * 3
                val n = out.size / frame
                val imgs = (0 until n).map { ImageOps.fromRgb(out, w, h, it * frame) }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    clearCreateResults()
                    createResults += imgs
                    createSeeds += (0 until n).map { seed + it }
                    setBusy(false, "Done: $n image${if (n == 1) "" else "s"} in ${"%.0f".format(secs)}s")
                    if (mode == 1) showResultAt(0) else updateUi()
                }
            } catch (t: Throwable) {
                markGpuInFlight(false)
                ui.post { setBusy(false, "Failed: ${t.message}"); pollNative(); gpuFailureHint() }
            } finally {
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun saveResult() {
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
        busy = b
        txtStatus.text = status
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
        val imgLabel = if (batchSize > 1 && img > 0) "Image ${img.coerceAtMost(batchSize)}/$batchSize · " else ""
        if (step > 0 && total > 0) {
            progress.isIndeterminate = false
            progress.max = total
            progress.progress = step
            val t = SdNative.lastStepTime()
            val speed = if (t > 0) " · ${"%.1f".format(t)}s/it" else ""
            txtStatus.text = imgLabel + "Step $step/$total$speed" + if (stage.isNotEmpty()) " · $stage" else ""
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
        btnGenerate.isEnabled = !busy && loaded && (if (mode == 1) stylizeSource != null else canvas.image != null)
        btnTabInpaint.isEnabled = !busy
        btnTabCreate.isEnabled = !busy
        if (mode == 1) canvas.previewHint = "Tap Photo to choose a picture to stylize"
        btnCancel.isEnabled = busy && loaded
        btnLoadModel.isEnabled = !busy && modelFile != null
        btnSave.isEnabled = !busy && currentResult() != null
        btnUseResult.isEnabled = !busy && currentResult() != null
        btnToggle.isEnabled = canvas.result != null
        btnToggle.text = if (canvas.showResult) "Original" else "Result"
        if (mode == 1) {
            rowResults.visibility = if (createResults.isNotEmpty()) View.VISIBLE else View.GONE
            if (createResults.isNotEmpty()) {
                txtResult.text = "Result ${createIndex + 1}/${createResults.size} · seed ${createSeeds[createIndex]} · hold to compare"
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
            val tail = try { prev.readLines().takeLast(15) } catch (_: Throwable) { emptyList() }
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
    private fun applyGpuEnv() {
        val safe = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("gpuSafe", true)
        val flags = listOf("GGML_VK_DISABLE_COOPMAT", "GGML_VK_DISABLE_COOPMAT2",
            "GGML_VK_DISABLE_INTEGER_DOT_PRODUCT", "GGML_VK_DISABLE_BFLOAT16")
        try {
            for (f in flags) {
                if (safe) android.system.Os.setenv(f, "1", true) else android.system.Os.unsetenv(f)
            }
        } catch (_: Throwable) { }
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
    }
}
