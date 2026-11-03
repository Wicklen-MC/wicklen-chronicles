# redz-spirit

Летающий дух-компаньон с LLM для Minecraft 26.3 + Fabric. Сервер redz-v1.

Дизайн и статус: [../docs/12-ai-spirit-mod.md](../docs/12-ai-spirit-mod.md).
Разбор чужих исходников: [../research-mods/SOURCES-REPORT.md](../research-mods/SOURCES-REPORT.md).

## Возможности

- Дух (translucent светящийся куб-светлячок) кружит вокруг игрока, сквозь стены,
  неуязвим, не деспавнится; один на игрока: появляется при входе, исчезает при выходе.
- Команды: `/spirit summon|dismiss`, `/spirit persona <firefly|fox|owl|cat>`,
  `/spirit admin test <текст>` (ops).
- Чат: `@дух <текст>` — диалог; реплики по событиям мира (ночь/рассвет/полнолуние/смерть).
- LLM: любой OpenAI-совместимый API (по умолчанию DeepSeek). Ключ только из env.

## Сборка

```bash
JAVA_HOME=../tools/jdk-25.0.5+8/Contents/Home GRADLE_USER_HOME=../tools/gradle-home ./gradlew build
# -> build/libs/redzspirit-<version>.jar
```

JDK 25 (Temurin, `../tools/`), Gradle wrapper 9.8.2, Mojang-маппинги.

## E2E-тест

```bash
tools/e2e.sh   # mock-LLM + dev-сервер + RCON; PASS = полный LLM-контур работает
```

## Переменные окружения сервера

Активный провайдер — `SPIRIT_PROVIDER` (default `deepseek`); у каждого свой namespace:

| Провайдер | Переменные |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL` (default `deepseek-flash`), `DEEPSEEK_BASE_URL`; alias: `SPIRIT_API_KEY/SPIRIT_MODEL/SPIRIT_BASE_URL` |
| OpenAI | `OPENAI_API_KEY`, `OPENAI_MODEL` (default `gpt-4o-mini`), `OPENAI_BASE_URL` |
| Ollama (локально) | `SPIRIT_PROVIDER=ollama`, `OLLAMA_BASE_URL` (default `127.0.0.1:11434`), `OLLAMA_MODEL` (default `qwen2.5:3b`) |
| Любой OpenAI-совместимый | `CUSTOM_BASE_URL`, `CUSTOM_MODEL`, `CUSTOM_API_KEY` + `SPIRIT_PROVIDER=custom` |

Проверка из консоли: `/spirit admin providers`. Ключи только в env, не в конфигах/git.

## Лицензия

MIT (свой код с нуля; идеи из Spirit Companions (ARR) и mc_aiplayer/Friendai (MIT)).
