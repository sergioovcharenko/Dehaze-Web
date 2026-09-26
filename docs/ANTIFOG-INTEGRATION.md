# Offline AntiFog engine — інтеграція коду

`Meti-AntiFog-Code.zip` містить Android library module з вихідним Java-кодом, локальною ONNX-моделлю, метаданими її перевірки та Gradle-конфігурацією. `Meti-AntiFog-Engine.aar` — скомпільований Android-модуль. APK — лише стенд для перевірки.

Android API 26+, Java 17. Для вихідного модуля: додати `include ':engine'` у settings.gradle і `implementation project(':engine')` у додаток. Кореневий Gradle-проєкт має оголосити Android library plugin 8.5.2 або сумісний. Для готового AAR: `implementation files('libs/Meti-AntiFog-Engine.aar')` і `implementation 'com.microsoft.onnxruntime:onnxruntime-android:1.23.2'`. Репозиторії: google(), mavenCentral(). Залежності завантажуються під час збірки; обробка на пристрої не використовує інтернет.

```java
import ua.dehaze.live.AntiFogEngine;

AntiFogEngine engine = new AntiFogEngine(context); // starts disabled
engine.setEnabled(true);                         // ANTI-FOG ON
// Single background worker, at most one frame being processed:
AntiFogEngine.Result result = engine.process(bitmap, 0.70f, false);
// On UI thread, before displaying: engine.isCurrent(result).
// result.image, result.algorithmName, result.strength, result.processingMs.
// When no longer displayed: result.close(); never recycles the input bitmap.
engine.setEnabled(false); // immediately show host's original frame
engine.reset();           // source/stream/configuration change
// After pending work finishes, on worker: engine.close();
```

Хост відповідає за декодування, захоплення, орієнтацію, часові мітки та відображення. Не передавати нові кадри у необмежену чергу: брати найновіший доступний після завершення попереднього. Не змінювати/звільняти вхідний Bitmap, поки process працює. Після OFF хост одразу показує поточний оригінал і відкидає попередні результати за isCurrent(). Результат може мати менші розміри за вхід; межі в LAB4-QUICKSTART.md. `photo=true` запускає повне порівняння для кожного виклику; `false` використовує періодичні проби.

В AUTO доступні CLASSIC, BC/CR, DehazeFormer-T, CAP, FAST та повернення оригіналу. Сила порівнюється на рівнях 0%, 35%, 65%, 100% від заданої межі. Повторного запуску AI для кожного рівня сили немає. Це евристика, не гарантія правильного вибору.

Для Qt/C++ QGroundControl потрібен адаптер між відеокадром Qt/GStreamer та Android-модулем (наприклад JNI). Цей пакет ще не містить готового адаптера, кнопки QGC чи зміненого QGC APK. Вбудовування спиратиметься виключно на APK користувача; існуючий сторонній проєкт не є базою.

CAP: Zhu, Mai, Shao, IEEE TIP 2015, DOI 10.1109/TIP.2015.2446191. Тут застосовано опубліковані коефіцієнти з нульовим випадковим шумом, зменшені карти та guided filter по яскравості. Це документована адаптація, не бітова копія авторського MATLAB.

Модель DehazeFormer має ліцензію MIT; її походження і паритет ONNX записані в assets/dehazeformer-t-outdoor-256.json. Ліцензії моделі та застосованих джерел додані до пакета.
