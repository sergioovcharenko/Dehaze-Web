# AUTO execution record

- User explicitly requested automatic algorithm switching. Implement the existing flow without another authorization request.
- Base: f53a6f78cc2a9a460ff80f5bc53ba7c3aed06b0f, delivered APK with all 20 unit and 6 emulator tests green.
- Plan: 2026-09-26-lab3-auto.md. Implement inline; one final independent review.
- AutoQuality/AutoPolicy test-first: missing classes failed compilation, then 14/14 JUnit tests passed locally via ECJ/JRE17. Tests cover detail/noise/clipping and selection/hysteresis/failure/cooldown/reset.
- Android automatic-flow tests committed before Activity changes; CI tests the missing default AUTO and automatic photo launch.

- CI RED run 36235907499: defaultAutoProcessesPhotoWithoutPressingProcess failed on missing AUTO selection; disabledAutoDoesNotProcessNewPhoto failed waiting for automatic processing after re-enable. The previous 6 Android tests and new manual-mode test passed. Full unit suite passed.
- AUTO Activity implementation now queues photo once, probes video every 6 seconds after the last finished probe, commits policy only after the existing generation gate accepts the result, and retains per-algorithm failure reports. Normal AUTO frames run one candidate; original is available without a candidate.
- Per-candidate isolation tests were written before AutoProcessing. These Android-only tests will run in the full emulator suite; no claim of a separate RED runtime observation for that helper.
- Package ua.meti.tuman.lab3.auto, artifact Meti-Tuman-LAB3-AUTO.apk. Uploaded QGC APK received and hashed; user explicitly asked to finish AUTO first, then integrate into QGC separately.
