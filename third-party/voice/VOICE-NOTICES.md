# Bundled offline voice

ODCM includes Kokoro 82M v1.0, American male `am_michael` (speaker 16),
converted for sherpa-onnx. The unquantized model avoids known ARM int8 audio
corruption reports. Source model: https://huggingface.co/hexgrad/Kokoro-82M
Conversion: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.3/scripts/kokoro
The model's Apache 2.0 license is included with the APK assets.

Native runtime: sherpa-onnx v1.13.3 (Apache 2.0), including ONNX Runtime
(MIT) and eSpeak NG (GPL version 3 or later). eSpeak is used for phonemization.
Individual components retain their copyright and license notices. The combined
voice-enabled binary is distributed subject to GPLv3 requirements; source
components retain their respective compatible licenses.

Corresponding source and build instructions:
- ODCM: https://github.com/ether4o4/android-ui-access-
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.3
- eSpeak: https://github.com/csukuangfj/espeak-ng/tree/f6fed6c58b5e0998b8e68c6610125e2d07d595a7
- ONNX Runtime: the version and archive hashes used for each Android ABI are
  recorded in sherpa-onnx's build-android-*.sh files.

Run `python3 tools/prepare-bundled-voice.py`, then the normal Gradle build.
To rebuild the native library, follow the pinned sherpa-onnx source's
`.github/workflows/android.yaml`; it includes the Android NDK build scripts,
dependency versions/hashes and AAR assembly procedure. No proprietary model
service is required. Source archives accompany the 0.3.6 release.
