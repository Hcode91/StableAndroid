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

### Settings worth knowing

| Setting | What it does |
|---|---|
| Inpaint area: **Only masked** | Crops around the mask, generates that region at the working size and pastes it back, so big photos keep their resolution and small details get more pixels. **Whole image** fits the entire photo into the working size. |
| Working size | Long side of the generated region (512 is native for SD 1.5). Bigger = slower and more RAM. |
| Denoise | 1.0 for inpainting checkpoints. With a regular (non-inpainting) SD 1.5 model use ~0.6–0.8. |
| Weights in memory | **F16** is the default (~2 GB RAM for the UNet+CLIP+VAE). **Q8_0 / Q4_0** quantise at load time to use less RAM and run a bit faster, with some quality loss. |
| Threads | 4 is usually fastest on phones (the big cores). Try 6–8 on flagships. |
| Memory-map file | Lets the OS page the weights from storage instead of copying them. Can lower RAM use; turn it off if loading fails. |

Regular SD 1.5 checkpoints also work (masked img2img), but dedicated inpainting checkpoints
blend far better.

## Expectations

This runs on the CPU. On a recent flagship expect very roughly 2–6 s per step at 512×512,
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
