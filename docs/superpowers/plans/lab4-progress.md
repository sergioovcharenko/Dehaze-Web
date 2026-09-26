# LAB 4 delivery verification

Base: delivered LAB 3 AUTO, local d19aea1 / remote e33095e1c5baa19e2b02f70a5f2808c5c8028065. Separate branch feat/lab4-auto; old APKs and main unchanged.

Implemented: deterministic CAP, FAST DCP, five-candidate AUTO, strength search within user ceiling, Android library and public AntiFogEngine API, source ZIP packaging, separate LAB 4 APK.

Validation:

- Observed RED: 13 new pure tests could not compile before LightDehaze/AutoStrength and configurable AutoPolicy existed.
- GREEN: 39 local pure tests, including all previous pure tests.
- Initial CI run 36256758884 built APK/AAR, verified bundled model parity/hash and APK signature/permissions, and completed 20 Android tests successfully. Overall run was cancelled during later workflow steps when the reviewed fix was pushed; it is not represented as a fully successful final run.
- Final code tree ae7ef166859f6ff643b70fd05fc12212c4b6c531; remote a829a1e19e93bba9417c2618849b5ea1f99e0df9. Final CI 36257022460 completed successfully: 47 unit tests and 22 Android emulator tests (69 total). Job 108445626933; final Android tests finished 2026-09-26T16:56:55Z. Package/permissions, signature and model parity all verified.

One independent final review: no Critical findings. Important finding accepted and fixed: manual Compare 5 previously stopped on AI failure and failed to reach CAP/FAST. It now catches failures independently, keeps successful pairs, and reports the successful count and per-candidate errors. Added injected-AI-failure regression plus resized-caller-bitmap ownership check. No second review requested.

Explicit limitations / deferred review notes:

- Partial candidate bitmaps on cancellation become garbage-collectable; explicit immediate cleanup on every cancellation path is deferred. Repeated cancellation under real-device memory pressure is not validated.
- Quality selection is untrained/no-reference. Synthetic tests do not establish superiority on real fog, snow, sky or night footage.
- LAB 4 heavy video remains matched snapshots up to 256 px. Existing CLASSIC LIVE is separate. Neither this APK nor the AAR establishes full-rate low-latency QGC processing.
- AAR is Android/Java, minimum API 26. Qt/GStreamer needs an adapter; none is included. No modified QGC APK has been produced.
- Automatic candidate cooldown checks elapsed time after completion; it is not a hard inference timeout. Displayed processing duration is not camera-to-display latency.
- Physical tablet performance and real-fog perceptual quality remain unverified.


Delivered artifacts (all saved successfully with local metadata applied):

| File | Bytes | SHA256 | Library ID |
|---|---:|---|---|
| Meti-Tuman-LAB4-AUTO.apk | 22751478 | da7d9620c78835b9e9296f4a71a114eece7ab2c237ed898fe74219c9b25327ef | libfile_dabc384417fc8191b504dbf1ca7c4149 |
| Meti-AntiFog-Engine.aar | 2641636 | 715bbae864b04e53f3baddc79b673de4a09477f0b679f983b259b6c09de3f85a | libfile_d5eee6c58fa881919b4ea791fd29b65d |
| Meti-AntiFog-Code.zip | 2626942 | e0a673e631e6f5a4fe97b5a298b5d6c6531eecaaff69ae26daed95011e53f3c8 | libfile_43d611e0aec8819198442412e3c3baf0 |

Delivery artifact 10910998173; ZIP digest bc34cc1388ff15f8866fe8c58abfa03a7d4e370e7072280d41a763e8a6461a46. Test report artifact 10910364711. Independent archive checks confirmed equal model bytes in APK, AAR and source ZIP; AAR contains processing classes and no Activity classes. Model SHA256 b27aca33df142eac35a75d4a94cb2d667196140df2ceca36298d6da0d9839ab3; maximum tested ONNX parity error 1.6808509826660156e-05 (<0.001).
