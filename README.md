# redz-spirit

Летающий дух-компаньон с LLM для Minecraft 26.3 + Fabric. Сервер redz-v1.

Дизайн и статус: [../docs/12-ai-spirit-mod.md](../docs/12-ai-spirit-mod.md).
Разбор чужих исходников: [../research-mods/SOURCES-REPORT.md](../research-mods/SOURCES-REPORT.md).

## Возможности

- Дух — анимированный светлячок с глазами, плавниками, хвостом, венчиком и частицами.
  Медленно кружит в 3,1 блока от игрока, проходит сквозь стены,
  неуязвим, не деспавнится; один на игрока: появляется при входе, исчезает при выходе.
- Команды: `/spirit summon|dismiss`, `/spirit persona <firefly|fox|owl|cat>`,
  `/spirit admin test <текст>` (ops).
- Чат: `@дух <текст>` — диалог; реплики по событиям мира (ночь/рассвет/полнолуние/смерть).
- **G** — окно диалога с историей; прокрутка колесом или ↑/↓, отправка Enter.
- Реплики видны над духом 6–20 секунд. Приветствие при входе и `/spirit summon`
  работают без LLM и позволяют проверить облачки.
- Персона задаёт характер и цвет; дух восстанавливается после телепорта и смены измерения.
- LLM: любой OpenAI-совместимый API (по умолчанию DeepSeek). Ключ только из env.

## Сборка

```bash
JAVA_HOME=../tools/jdk-25.0.5+8/Contents/Home GRADLE_USER_HOME=../tools/gradle-home ./gradlew build
# -> build/libs/redzspirit-<version>.jar
```

JDK 25 (Temurin, `../tools/`), Gradle wrapper 9.8.2, Mojang-маппинги.

## E2E-тест

`./gradlew build` также запускает регрессионные проверки лимитов, JSON-хранилища,
конфига и OpenAI/Ollama HTTP-протоколов на localhost, без ключей и внешнего API.

Клиентская проверка в отдельном мире (артефакт мода не включает тестовый код):

```bash
JAVA_HOME=../tools/jdk-25.0.5+8/Contents/Home GRADLE_USER_HOME=../tools/gradle-home ./gradlew runClientGameTest
# Скриншоты: build/client-gametest/screenshots/
```

Проверяет модель и диалог, доставку облачков, полный C2S → mock-LLM → S2C контур,
один запрос в полёте, обработку HTTP-ошибок, квесты без LLM, повторный призыв,
удаление, телепорт и переход в другое измерение. Порты mock-серверов выбираются
автоматически; используются только временные игровые данные в `build/`.

```bash
tools/e2e.sh   # mock-LLM + dev-сервер + RCON; PASS = полный LLM-контур работает
```

## Переменные окружения сервера

При первом запуске создаётся `config/redzspirit.json`: провайдеры, `cooldownMs`,
`maxTurnsPerHour`, `maxTokens`, `temperature`, `proactive`. Изменения применяются
при перезапуске сервера. `proactive: false` отключает автоматические LLM-реплики,
сохраняя прямой диалог и детерминированную работу заданий. Ссылки `env:NAME`
позволяют хранить ключ в окружении; переменные из таблицы переопределяют конфиг.

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
