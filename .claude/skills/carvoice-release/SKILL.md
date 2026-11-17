---
name: carvoice-release
description: Собрать, выпустить или откатить CarVoice. Использовать, когда пользователь пишет «собирай», «собери», «делай стабильной», «откат», «дай ссылку».
---
# Сборка и выпуск CarVoice

Репозиторий vitodotcom-create/Hi, ветка `claude/agents-skills-usage-7r2req`.

## «собирай»
1. Если есть незакоммиченные правки — коммит (без `[skip ci]`) и push; иначе запустить workflow `build-apk.yml` через `mcp__github__actions_run_trigger` (run_workflow, ref = ветка).
2. Ждать в фоне: `until curl -s "https://api.github.com/repos/vitodotcom-create/Hi/actions/runs?per_page=1" | python3 -c "import json,sys;d=json.load(sys.stdin)['workflow_runs'][0];sys.exit(0 if d['run_number']>=N and d['status']=='completed' else 1)"; do sleep 20; done` (run_in_background).
3. Проверить conclusion=success, взять тег последнего релиза.
4. Ответ: «Тестовая версия X», ссылки `https://github.com/vitodotcom-create/Hi/releases/download/<тег>/CarVoice.apk` и `.../CarVoicePanel.apk`, ЧТО ставить (только изменённое), «поверх, без удаления», что проверить. Напомнить про Play Защиту.

## «делай стабильной»
`echo <тег> > STABLE_VERSION`, коммит с `[promote]`, push. Дождаться, проверить `releases/latest`. Дать ссылки `releases/latest/download/...`.

## «откат»
Дать ссылки на стабильную: `releases/latest/download/CarVoice.apk` и `CarVoicePanel.apk`.

## Правила
- Без слова «собирай» сборку не запускать (коммиты с `[skip ci]`).
- Перед сборкой Kotlin можно проверить локально (scratchpad `pcheck`, gradle compileKotlin).
