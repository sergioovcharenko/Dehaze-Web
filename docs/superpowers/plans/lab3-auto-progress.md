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
- Independent review complete: no Critical; two Important accepted (manual spinner released frozen comparison; source change during test retained hold). New emulator regressions added before their fixes.
- Final: minor (deferred): manualModeIgnoresQueuedAutoDecision exercises delayed decode rather than an already-running inference. The off-during-inference test and pure generation-gate tests exercise cancellation; an additional manual-during-inference scenario remains future test coverage.
- Final: Ruling: unverified physical-tablet performance and real-fog perceptual quality — deliver as tested heuristic AUTO, do not promise perceptual optimality or hardware FPS — cost if wrong: user may prefer a manual mode for a scene.
- Final: Ruling: QGC/full-rate heavy processing — separate next stage, snapshot labels retained — cost if wrong: AUTO is unsuitable where current-frame full-rate video is required.
- Final: Ruling: manual Compare 3 partial failure remains inherited behavior; new independent-candidate fallback applies to AUTO — cost if wrong: manual comparison must be retried after a candidate failure.

- RED review regression run 36236532035 executed 15 emulator tests; comparison browsing and photo selection during a video test failed. The other 13 passed; 34 unit tests passed.
- Fix pass: resetAuto no longer releases held comparison; manual spinner browsing keeps the held frame. Explicit AUTO choice/on-off/strength/source/Process actions release it. invalidate finishes the prior test before resetting the new source's state. Final GREEN run pending.
