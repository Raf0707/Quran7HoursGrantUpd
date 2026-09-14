# Validation report — Android v1.4

Проверено перед упаковкой патча:

- База патча: Android v1.3.1 Gradle Fix.
- Суры: 114 / 114; аяты: 6236.
- `page-index.json`: 604 страницы, ссылки на аяты валидны.
- Word-by-word: 604 / 604 JSON, 77 429 записей.
- Геометрия мусхафа: 604 / 604 страниц.
- Hadr page timings: 604 / 604; Hadr остаётся глобальным, автоперелистывание отключено.
- Все 723 JSON исходного корпуса парсятся без ошибок.
- Все Android XML парсятся без ошибок.
- Python offline-QPC scripts: `py_compile` OK.
- Kotlin parser-level check модифицированных `.kt`: синтаксических `expecting/unexpected tokens` ошибок не обнаружено (Android/Compose classpath в контейнере отсутствует, поэтому это не полноценная Kotlin compilation).
- `git diff --check`: OK.
- Удалены `audio.queue`, `setQueue()` и отдельное состояние кнопки «Далее»: мини-плеер использует `AppSettings.autoAdvance` напрямую.
- Повторный Play после `Player.STATE_ENDED` перематывает текущий media item к 0 и снова запускает его.
- QPC runtime network fetch отсутствует. QPC page/layout/typeface читаются из `AssetManager`; текущая страница и ±2 соседние прогреваются в памяти.
- Gradle не скачивает QPC assets. `preBuild` выполняет только локальную проверку 604 layout + 604 V4 + 604 V2 файлов.
- Полный офлайн-QPC pack в контейнер не вложен в patch bundle: перед локальной сборкой его готовит `tools/prepare_quran_offline_assets.py`. Это также предотвращает выпуск APK, где V4 Tajweed случайно присутствует только для page 1.

Ограничение среды: Android SDK/Gradle distribution не доступны, а исходящий DNS Gradle заблокирован, поэтому `assembleDebug` здесь не запускался.
