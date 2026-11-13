# StableAndroid

On-device **Stable Diffusion 1.5 inpainting** for Android. Load any SD 1.5 `.safetensors`
checkpoint straight from your phone's storage, paint a mask over a photo, type a prompt,
and the masked area is regenerated locally (no server, no internet).

Powered by [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) (ggml, CPU)
via JNI.

## Getting the APK

Every push builds an APK with GitHub Actions. Open the latest run under **Actions → Build APK**
and download the `StableAndroid-apk` artifact. It is signed with a debug key, so you can install
it directly (allow installs from unknown sources).

Requires Android 10+ on a 64-bit ARM phone (ARMv8.2 with dotprod — roughly 2019 or newer).

## Using it

1. **Choose model** → pick a `.safetensors` file (e.g. `sd-v1-5-inpainting.safetensors`
   or any SD 1.5 *inpainting* fine-tune). It is copied into the app's storage once (only the
   current model is kept, so you can delete your original afterwards) and then loads automatically; the status line shows the
   detected type, e.g. `SD 1.x Inpaint`.
2. **Image** → open a photo. Paint over what you want changed (one finger paints, two fingers
   pan/zoom, **Brush/Eraser** toggles, **Undo / Clear / Invert**).
3. Enter a prompt and tap **Inpaint**. Tap **Result/Original** to compare, **Save** to write a
   PNG to `Pictures/StableAndroid`, or **Keep & continue** to inpaint again on top of the result.

You can also share an image into StableAndroid from the gallery.

### Img2Img tab

Redraws a whole photo from a prompt. Tap **Photo**, describe what it should become and/or pick a
**Style** preset (Anime, Cartoon, 3D game character, Pixel art, Chibi, Comic book, or None), and
tap **Generate**. **Denoise** controls how far it moves from the photo: ~0.45 stays close, ~0.7
changes a lot. Press and hold a result to compare with the original; **Edit in Inpaint** sends it
to the Inpaint tab.

### Restore original

After an inpaint (or an Img2Img run), **Restore original (paint it back)** lets you paint over
parts of the result to bring back the untouched pixels of the photo it was made from, with a
soft-edged brush, the eraser and undo. Tap **Done restoring** to apply. It also works on an image saved earlier: choose the edited image, then the original it was made from (the two must be the same shape; the original is fitted over the edit automatically). Useful when an edit has
softened a face or detail you wanted to keep.

### Inpaint queue

To inpaint several photos unattended: open a photo, paint its mask, set the prompt and tap
**Add to queue**; repeat for each photo; then tap **Run queue**. Each photo keeps the prompt and
settings it was added with, results are saved to `Pictures/StableAndroid` as they finish, and
**Stop** lets the current photo finish first. The queue is not kept if the app is closed.

### Video tab (experimental)

Image-to-video with AnimateDiff: 8–16 frames at 8 fps, saved as a looping GIF. Needs a motion
module (`mm_sd15_v3.safetensors`, about 836 MB) chosen under **Model → Choose motion module**,
then **Load** tapped while on the Video tab. Uses normal sampling (Euler, Karras, about 20 steps,
CFG 7); the LCM LoRA is not used. On a phone CPU this is very slow and may run out of memory.

### Text tab

Text to image: type a prompt, pick a **Size**, optionally a Style preset, and tap **Create**.
Use a normal SD 1.5 checkpoint here; inpainting checkpoints are made for editing and give weaker
results from text alone.

### Sharpen faces

In the Text and Img2Img tabs, **Sharpen faces** enlarges each result ×2, finds faces with Android's
built-in face detector and redraws each one at full working size (denoise 0.45), which fixes the
smeared eyes SD 1.5 gives small faces. It adds roughly one extra pass per face. Faces in profile
or very small faces may not be found; the image is then only enlarged.

### Describe image

**Describe image** (next to the prompt) writes a description of a picture into the prompt box
using Florence-2, on the device. In Inpaint and Img2Img it describes the open photo; in Text it
asks for a picture. It needs four model files from
[onnx-community/Florence-2-base-ft](https://huggingface.co/onnx-community/Florence-2-base-ft/tree/main/onnx)
(about 275 MB together), picked once under **Model → Choose describe files**:
`vision_encoder_quantized.onnx`, `embed_tokens_quantized.onnx`, `encoder_model_quantized.onnx`
and `decoder_model_quantized.onnx`.

### Checkpoint list

**Model → Checkpoints** lists the checkpoints in a folder you choose (files of 900 MB or more,
not sub-folders) together with the copies the app already holds. Tap one to load it. A checkpoint
is copied into the app the first time it is used and loads directly after that; **Delete a copy**
frees the app's copy without touching your original.

### Presets

Each tab has its own presets. **Save settings** stores the current sampler, scheduler, steps, CFG,
denoise, size, style, image count and negative prompt under a name; **Presets** lists them to
apply or delete. The prompt itself is not stored.

### Settings worth knowing

| Setting | What it does |
|---|---|
| Inpaint area: **Only masked** | Crops around the mask, generates that region at the working size and pastes it back, so big photos keep their resolution and small details get more pixels. **Whole image** fits the entire photo into the working size. |
| Edge feather | Inpaint tab. How wide the soft edge around the mask is, from sharp to very soft. A wider feather hides tone differences but needs more room around the mask. |
| Match colours to the surrounding area | Inpaint tab, on by default. Shifts the new patch so its average colour just outside the mask equals the original's, which evens out skin tone. |
| Working size | Long side of the generated region (512 is native for SD 1.5). Bigger = slower and more RAM. |
| Denoise | 1.0 for inpainting checkpoints. With a regular (non-inpainting) SD 1.5 model use ~0.6–0.8. |
| Weights in memory | **F16** is the default (~2 GB RAM for the UNet+CLIP+VAE). **Q8_0 / Q4_0** quantise at load time to use less RAM and run a bit faster, with some quality loss. |
| Threads | 4 is usually fastest on phones (the big cores). Try 6–8 on flagships. |
| Fast VAE (TAESD) | On by default. Uses a tiny bundled VAE to encode/decode in seconds instead of minutes, with slightly softer fine detail. Untick and tap **Load** for the model's full VAE. |
| Memory-map file | Lets the OS page the weights from storage instead of copying them. Can lower RAM use; turn it off if loading fails. |

Regular SD 1.5 checkpoints also work (masked img2img), but dedicated inpainting checkpoints
blend far better.

## LoRA (e.g. LCM LoRA)

Under **Model**, **Choose LoRA** adds one LoRA file on top of the loaded checkpoint; **Remove**
clears it and **Strength** is normally 1.0. It is applied on the next run, no reload needed.
The main use is the SD 1.5 **LCM LoRA** (`latent-consistency/lcm-lora-sdv1-5`), which lets any
SD 1.5 checkpoint, including inpainting ones, run in 4–8 steps with Sampler **LCM** and CFG 1–1.5.

## Running in the background

While a model is loading or images are generating, the app runs a foreground service with a
progress notification, so you can switch apps or turn the screen off. A notification tells you
when it finishes, and results that finish while the app is off-screen are also saved to
`Pictures/StableAndroid` automatically. If your phone still stops it, set the app's battery
usage to **Unrestricted** in Android's app settings.

## GPU (Vulkan)

Under **Model → Run on** choose **GPU (Vulkan)** and tap **Load**. The log lists the devices
found (e.g. `Vulkan0 Adreno (TM) 740`) and the status line says where the model loaded.
The first GPU run is slower because shaders are prepared.

Phone GPU drivers vary a lot. If the app closes while using the GPU, the next launch
switches back to CPU automatically and says so in the log.

### Turnip (custom Adreno driver)

On Snapdragon phones the stock Qualcomm driver may fail to build the GPU code. Under **Model**,
**Install Turnip (.zip)** takes the same driver packages emulators use (a `.zip` with `meta.json`
and the driver `.so`, e.g. from the K11MCH1/AdrenoToolsDrivers releases) and loads it with
libadrenotools. Close and reopen the app after installing or removing a driver. If the app
closes while a custom driver is in use, the driver is turned off on the next launch.

## Expectations

On the CPU, on a recent flagship expect very roughly 2–6 s per step at 512×512,
so a 20-step inpaint takes about one to two minutes. Keep the app in the foreground while it works
(the screen is kept on automatically). If Android kills the app during load, pick Q8_0 or Q4_0.

The **Log** section at the bottom shows what stable-diffusion.cpp is doing; **Copy log** puts it on
the clipboard for bug reports.

## Building

```bash
./scripts/fetch_sdcpp.sh        # fetches stable-diffusion.cpp at the pinned commit
./gradlew assembleRelease
```

The stable-diffusion.cpp commit is pinned in `scripts/fetch_sdcpp.sh` because its C API changes
frequently; bump it deliberately and rebuild.

## Licence notes

stable-diffusion.cpp and ggml are MIT licensed. Model weights come with their own licences
(SD 1.5 uses CreativeML OpenRAIL-M).
