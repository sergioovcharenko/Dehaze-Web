# LAB 3 execution record

Plan: `2026-09-26-lab3-apk.md`. Base: `026d47c1dd59993b15635b75fcc5ceb20cda056d`.

- User explicitly requested a separate APK with every mode, comparison against previous apps, and short setup instructions. Latest prompt asks to create the APK; continue implementation without another task-authorization request.
- Source copied into android-lab3. Old Android/web folders unchanged.
- BC/CR, frame-generation gate and tensor conversion tests were added before their implementations; missing implementations failed compilation, followed by 12 passing JUnit tests using ECJ/JRE 17 locally.
- Official outdoor-T weights obtained: SHA-256 `156497e21bbfd3bf408067b2734db1427f2a5722f3a39924ccbfaabaa59ab0da`.
- ONNX export succeeded; three comparisons against original PyTorch had maximum absolute errors 0.00001705, 0.00000310, 0.00000393 (limit 0.001).
- The pure CPU CLASSIC snapshot adapter is new code; its instrumentation coverage was added after the adapter. The original live renderer and photo editor are retained as separate entry points. Do not claim strict test-first coverage for this adapter.
- Ruling: all new algorithms are available in a matched-snapshot workbench with camera/video test sources; preserve the original GLES CLASSIC for smooth live view. Delayed neural output is labelled with its age, not shown as current live video. Cost: AI/BC are not yet full-rate replacements for CLASSIC live.
- Ruling: complete APK first; a new web-LAB remains subsequent work. The current user repeated the APK request.
- Remaining: CI full suite/build, Android emulator inference/lifecycle tests, independent review, APK download/verification, delivery.
