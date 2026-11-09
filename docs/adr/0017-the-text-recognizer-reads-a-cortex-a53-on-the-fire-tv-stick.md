---
status: superseded by 0018 (2026-11-08)
date: 2026-11-08
---

# The text recognizer reads a Cortex-A53 on the Fire TV Stick

Amends [ADR 0016](0016-title-treatments-cut-from-the-providers-posters.md), which cuts the Title treatment out of the poster with ML Kit's bundled text recognizer.

The first release with treatments (0.1.770) crashed the Fire TV Stick on its first poster read: `SIGILL` on `vsdot.s8` in `libmlkit_google_ocr_pipeline.so`, five times in four minutes. The stick (AFTKA, MT8696) is a Cortex-A55 r2 running 32-bit Fire OS 7 on a 32-bit 4.14 kernel, with `armeabi-v7a` its only ABI. The core has the ARMv8.2 dot product; the recognizer's library embeds the cpuinfo library, which on 32-bit ARM guesses the dot product from the core's model in `/proc/cpuinfo` (Cortex-A55 r1 and later), since no 32-bit kernel reports it; and the kernel is what kills the process: on SMP it turns the FPU off at every context switch and, when the next FPU instruction traps, decodes it to tell NEON from an undefined instruction, knowing only the classic NEON encodings. `vsdot` is in a newer encoding space, so the first time a thread resumes on one, the kernel raises `SIGILL`. No catch stops a native signal.

So on such a device the app shows the recognizer a **disguised `/proc/cpuinfo`**: before the recognizer first runs, the app detects the trap (a 32-bit ARM process, a dot-product core by model, no `asimddp` among the features), writes a copy of `/proc/cpuinfo` naming every core a Cortex-A53 r0 (an ARMv8 core without the dot product), loads the recognizer's library and patches its own imports of `open` and `fopen` so a read of `/proc/cpuinfo` opens the copy. The recognizer then settles for the plain NEON kernels every ARMv7 device runs. The library is as Google ships it; the redirect is one C file in the app, its only native code. Where the redirect can't be put in place, the recognizer stays off and every Title is named in type.

## Considered

- **Treatments off on such devices**: the fallback this amends; the stick is the household's device, so the feature would be off where it matters most.
- **Another ML Kit version**: the 16.0.0 library carries the same dot-product kernels (360 `vsdot` to 17.0.0's 660), and the Play-services recognizer needs Google Play services, which Fire OS lacks.
- **A 64-bit process**: the stick's Fire OS has no 64-bit ABI.
- **Another recognizer** (Tesseract, a TFLite text detector): a second OCR to tune on stylised lettering, and anything on TensorFlow Lite carries the same cpuinfo guess.
- **Cutting treatments in the cloud**: today nothing about a Title leaves the device for this (ADR 0016), and it would be a cost per Title.
- **Patching the library's file**: changes Google's binary; redirecting a file read in our own process leaves it untouched.

## Consequences

- The app builds native code for the first time: `app/src/main/cpp/recognizer_cpu.c` through ndk-build, the NDK version pinned in `build.gradle.kts`; CI installs that NDK.
- The redirect applies only where the trap is detected, read once per launch from the ABI and `/proc/cpuinfo`; 64-bit devices, x86 and cores without the dot product run the recognizer as before. The list of dot-product cores is kept by hand, for 32-bit processes only.
- On the stick the recognizer runs its plain NEON kernels, slower than the dot product would have been: still once per Title, off the main thread. Its cost there is measured with the rest of ADR 0016's (a later issue).
- The disguise also hides the FP16 arithmetic and the rounding multiplies from the recognizer; the model and its results are the same, so the cut-outs are.
- Checked on the stick (debug build 0.1.772, 2026-11-08): the recognizer read the copy from its own pipeline thread and read four posters of the hero's Title without a crash, 0.4 to 1.1 s each; and on an arm64 emulator with the redirect forced, where it cut a treatment.
