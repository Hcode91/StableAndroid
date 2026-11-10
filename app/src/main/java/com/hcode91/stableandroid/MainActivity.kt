package com.hcode91.stableandroid

import android.app.Activity
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
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
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
    private lateinit var chkBlend: CheckBox
    private lateinit var chkFlash: CheckBox
    private lateinit var chkMmap: CheckBox

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())

    private var modelUri: Uri? = null
    private var modelPfd: ParcelFileDescriptor? = null
    private var loadedModelName: String? = null
    private var busy = false
    private var lastSeed = -1L
    private val logBuffer = StringBuilder()

    private val poller = object : Runnable {
        override fun run() {
            pollNative()
            if (busy) ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()
        setupSpinners()
        restorePrefs()
        setupListeners()

        thread {
            val info = try { SdNative.systemInfo() } catch (t: Throwable) { "native library failed to load: $t" }
            ui.post { appendLog(info) }
        }
        handleIncomingIntent(intent)
        updateUi()
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
            modelPfd?.close()
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
        chkBlend = findViewById(R.id.chkBlend)
        chkFlash = findViewById(R.id.chkFlash)
        chkMmap = findViewById(R.id.chkMmap)
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
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnImage).setOnClickListener { pickImage() }
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
            SdNative.cancel()
            txtStatus.text = "Cancelling…"
        }
        btnSave.setOnClickListener { saveResult() }
        btnUseResult.setOnClickListener {
            canvas.result?.let {
                canvas.setImage(it)
                updateUi()
                toast("Result is now the input image")
            }
        }
        btnLastSeed.setOnClickListener {
            if (lastSeed >= 0) editSeed.setText(lastSeed.toString())
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
        editPrompt.setText(prefs.getString("prompt", ""))
        editNegative.setText(prefs.getString("negative", "blurry, low quality, deformed, watermark, text"))
        editSteps.setText(prefs.getString("steps", "20"))
        editCfg.setText(prefs.getString("cfg", "7"))
        editStrength.setText(prefs.getString("strength", "1.0"))
        editSeed.setText(prefs.getString("seed", "-1"))
        editClipSkip.setText(prefs.getString("clipSkip", "-1"))
        val cores = try { SdNative.physicalCores() } catch (t: Throwable) { 4 }
        editThreads.setText(prefs.getString("threads", cores.coerceIn(1, 4).toString()))
        spinSampler.setSelection(prefs.getInt("sampler", 0))
        spinScheduler.setSelection(prefs.getInt("scheduler", 0))
        spinSize.setSelection(prefs.getInt("size", 1))
        spinArea.setSelection(prefs.getInt("area", 0))
        spinWeights.setSelection(prefs.getInt("weights", 0))
        chkBlend.isChecked = prefs.getBoolean("blend", true)
        chkFlash.isChecked = prefs.getBoolean("flash", true)
        chkMmap.isChecked = prefs.getBoolean("mmap", false)
        prefs.getString("modelUri", null)?.let { s ->
            val uri = Uri.parse(s)
            val stillAllowed = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (stillAllowed) modelUri = uri
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("prompt", editPrompt.text.toString())
            .putString("negative", editNegative.text.toString())
            .putString("steps", editSteps.text.toString())
            .putString("cfg", editCfg.text.toString())
            .putString("strength", editStrength.text.toString())
            .putString("seed", editSeed.text.toString())
            .putString("clipSkip", editClipSkip.text.toString())
            .putString("threads", editThreads.text.toString())
            .putInt("sampler", spinSampler.selectedItemPosition)
            .putInt("scheduler", spinScheduler.selectedItemPosition)
            .putInt("size", spinSize.selectedItemPosition)
            .putInt("area", spinArea.selectedItemPosition)
            .putInt("weights", spinWeights.selectedItemPosition)
            .putBoolean("blend", chkBlend.isChecked)
            .putBoolean("flash", chkFlash.isChecked)
            .putBoolean("mmap", chkMmap.isChecked)
            .putString("modelUri", modelUri?.toString())
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
            REQ_MODEL -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { }
                modelUri = uri
                savePrefs()
                updateUi()
                if (!busy) loadModel()
            }
        }
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMAGE)
    }

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        uri?.let { openImage(it) }
    }

    private fun openImage(uri: Uri) {
        thread {
            try {
                val bmp = ImageOps.load(contentResolver, uri)
                ui.post {
                    canvas.setImage(bmp)
                    txtStatus.text = "Image ${bmp.width}×${bmp.height}. Paint over the area to change."
                    updateUi()
                }
            } catch (t: Throwable) {
                ui.post { toast("Couldn't open image: ${t.message}") }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "model"
                val size = if (!c.isNull(1)) c.getLong(1) else -1L
                return if (size > 0) "$name (${"%.2f".format(size / 1_073_741_824.0)} GB)" else name
            }
        }
        return uri.lastPathSegment ?: "model"
    }

    // ---------------------------------------------------------------- model

    private fun loadModel() {
        val uri = modelUri ?: run { toast("Choose a .safetensors file first"); return }
        if (busy) return
        val threads = editThreads.text.toString().toIntOrNull() ?: 4
        val wtype = (spinWeights.selectedItem as Choice).value
        val flash = chkFlash.isChecked
        val mmap = chkMmap.isChecked
        val name = displayName(uri)
        setBusy(true, "Loading $name…")
        val started = System.currentTimeMillis()
        thread(name = "sd-load") {
            try {
                SdNative.freeModel()
                modelPfd?.close()
                modelPfd = null
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Can't open the model file")
                modelPfd = pfd
                // stable-diffusion.cpp needs a path; the fd keeps the SAF grant alive.
                val path = "/proc/self/fd/${pfd.fd}"
                val version = SdNative.loadModel(path, null, threads, wtype, flash, mmap)
                val secs = (System.currentTimeMillis() - started) / 1000
                ui.post {
                    loadedModelName = name
                    setBusy(false, "Loaded $version in ${secs}s")
                    if (!version.contains("Inpaint", ignoreCase = true)) {
                        appendLog("Note: \"$version\" isn't an inpainting model. It will still repaint the " +
                            "masked area, but an SD 1.5 *inpainting* checkpoint blends far better (use Denoise ~0.6–0.8).")
                        editStrength.setText("0.75")
                    }
                    updateUi()
                }
            } catch (t: Throwable) {
                modelPfd?.close(); modelPfd = null
                ui.post {
                    loadedModelName = null
                    setBusy(false, "Load failed: ${t.message}")
                    updateUi()
                }
            }
        }
    }

    // ---------------------------------------------------------------- generate

    private fun generate() {
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
        val maskCopy = mask.copy(Bitmap.Config.ALPHA_8, false)

        lastSeed = seed
        savePrefs()
        setBusy(true, "Preparing…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val started = System.currentTimeMillis()

        thread(name = "sd-gen") {
            try {
                val job = ImageOps.prepare(image, maskCopy, onlyMasked, size, 32)
                    ?: throw IllegalStateException("Paint a mask over the area you want to change first")
                ui.post { appendLog("Inpainting ${job.width}×${job.height} from region ${job.region.toShortString()}, seed $seed") }
                val out = SdNative.inpaint(job.rgb, job.mask, job.width, job.height, prompt, negative,
                    steps, cfg, strength, seed, sampler, scheduler, clipSkip)
                val gen = ImageOps.fromRgb(out, job.width, job.height)
                val final = ImageOps.composite(image, maskCopy, job, gen, blend)
                gen.recycle()
                val secs = (System.currentTimeMillis() - started) / 1000.0
                ui.post {
                    canvas.result = final
                    canvas.showResult = true
                    setBusy(false, "Done in ${"%.1f".format(secs)}s · seed $seed")
                    updateUi()
                }
            } catch (t: Throwable) {
                ui.post { setBusy(false, "Failed: ${t.message}") }
            } finally {
                maskCopy.recycle()
                ui.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private fun saveResult() {
        val bmp = canvas.result ?: return
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
        val step = p[0]; val total = p[1]
        val stage = SdNative.stage()
        if (step > 0 && total > 0) {
            progress.isIndeterminate = false
            progress.max = total
            progress.progress = step
            val t = SdNative.lastStepTime()
            val speed = if (t > 0) " · ${"%.1f".format(t)}s/it" else ""
            txtStatus.text = "Step $step/$total$speed" + if (stage.isNotEmpty()) " · $stage" else ""
        } else if (stage.isNotEmpty() && txtStatus.text.startsWith("Step").not()) {
            txtStatus.text = stage
        }
    }

    private fun appendLog(s: String) {
        logBuffer.append(s).append('\n')
        if (logBuffer.length > 30_000) logBuffer.delete(0, logBuffer.length - 30_000)
        txtLog.text = logBuffer.takeLast(6_000)
    }

    private fun updateUi() {
        val loaded = loadedModelName != null
        txtModel.text = when {
            loaded -> "Loaded: $loadedModelName"
            modelUri != null -> "Chosen: ${displayName(modelUri!!)} (not loaded)"
            else -> "No .safetensors chosen. Use an SD 1.5 inpainting checkpoint for best results."
        }
        btnGenerate.isEnabled = !busy && loaded && canvas.image != null
        btnCancel.isEnabled = busy && loaded
        btnLoadModel.isEnabled = !busy && modelUri != null
        btnSave.isEnabled = !busy && canvas.result != null
        btnUseResult.isEnabled = !busy && canvas.result != null
        btnToggle.isEnabled = canvas.result != null
        btnToggle.text = if (canvas.showResult) "Original" else "Result"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_IMAGE = 1
        private const val REQ_MODEL = 2
    }
}
