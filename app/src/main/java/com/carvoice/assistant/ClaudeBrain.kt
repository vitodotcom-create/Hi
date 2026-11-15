package com.carvoice.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONObject
import java.time.Duration

/** Установленное приложение, которое Claude может открыть. */
data class AppInfo(val label: String, val packageName: String)

/** Одно действие, которое нужно выполнить на боксе. */
data class Action(
    val type: String,
    val target: String,
    val query: String,
    val value: String,
)

/** Решение «автопилота»: нажать кнопку [index], цель достигнута (done) или подождать (wait). */
data class PilotDecision(val decision: String, val index: Int)

/** Ответ ассистента: фраза для озвучки + список действий. */
data class Reply(val say: String, val actions: List<Action>, val endConversation: Boolean = true)

/**
 * «Мозг» ассистента: отправляет распознанную фразу в Claude и получает
 * строго структурированный JSON с ответом и действиями.
 */
class ClaudeBrain(
    apiKey: String,
    private val model: String,
    /** Указание, на каком языке отвечать (зависит от настроек и наличия голоса). */
    private val languageNote: String = "",
) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(40))
        .maxRetries(1)
        .build()

    /**
     * @param history история текущего разговора; дополняется вопросом и полным ответом модели,
     * чтобы Claude помнил предыдущие реплики.
     */
    fun ask(
        userText: String,
        apps: List<AppInfo>,
        now: String,
        history: MutableList<MessageParam> = mutableListOf(),
    ): Reply {
        val userMsg = MessageParam.ofUser(userText)
        val messages = history + userMsg
        val message = try {
            client.messages().create(buildParams(messages, apps, now, withFallbacks = supportsFallbacks()))
        } catch (e: BadRequestException) {
            // Если сервер не принял параметр fallbacks — повторяем без него.
            if (!supportsFallbacks()) throw e
            client.messages().create(buildParams(messages, apps, now, withFallbacks = false))
        }
        history += userMsg
        // Ответ храним простым текстом (без блоков размышлений): такие блоки привязаны
        // к конкретному запросу, и сервер отклоняет их при повторной отправке.
        val replyText = message.content().mapNotNull { it.text().orElse(null)?.text() }.lastOrNull() ?: "{}"
        history += MessageParam.ofAssistant(replyText)
        // Держим последние ~8 реплик, начиная всегда с вопроса водителя.
        while (history.size > 16) {
            history.removeAt(0)
            history.removeAt(0)
        }
        return parse(message)
    }

    /** Выбирает, какую кнопку на экране нажать, чтобы приблизиться к цели [goal]. */
    fun chooseButton(goal: String, labels: List<String>, history: List<String>): PilotDecision {
        val buttons = labels.mapIndexed { i, l -> "$i: $l" }.joinToString("\n")
        val done = if (history.isEmpty()) "ничего" else history.joinToString(", ")
        val system = """
Ты управляешь приложением на Android-боксе в машине по поручению водителя, нажимая кнопки на экране.
Тебе дают цель, список кнопок на текущем экране (номер: подпись) и что уже нажато.
Ответь JSON:
- decision: click — нажать кнопку с номером index; done — цель уже достигнута (например, ведение по маршруту уже идёт); wait — экран ещё загружается или нужных кнопок пока нет; fail — цель недостижима.
- index: номер кнопки для click, иначе -1.
Никогда не нажимай кнопки, которые платят, звонят, отправляют сообщения, удаляют, выходят из аккаунта или меняют настройки. Не нажимай «Отмена», «Закрыть», «Назад», если это не нужно для цели.
""".trim()
        val user = "Цель: $goal\nУже сделано: $done\nКнопки на экране:\n$buttons"
        val schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("decision", "index")))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "decision" to mapOf("type" to "string", "enum" to listOf("click", "done", "wait", "fail")),
                        "index" to mapOf("type" to "integer"),
                    )
                )
            )
            .build()
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048L)
            .system(system)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .addUserMessage(user)
            .build()
        val message = client.messages().create(params)
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) return PilotDecision("fail", -1)
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.lastOrNull()
            ?: return PilotDecision("fail", -1)
        val json = JSONObject(text)
        return PilotDecision(json.optString("decision", "fail"), json.optInt("index", -1))
    }

    private fun supportsFallbacks(): Boolean =
        model.startsWith("claude-opus-5-5") || model.startsWith("claude-sonnet-5-5") ||
            model.startsWith("claude-fable-5")

    private fun buildParams(
        messages: List<MessageParam>,
        apps: List<AppInfo>,
        now: String,
        withFallbacks: Boolean,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(systemPrompt(apps, now, languageNote))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(replySchema()).build())
                    .build()
            )
            .messages(messages)
        if (withFallbacks) {
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun parse(message: Message): Reply {
        if (message.stopReason().orElse(null) == StopReason.REFUSAL) {
            return Reply("С этим я помочь не могу.", emptyList())
        }
        // Сначала в ответе может идти блок размышлений — берём последний текстовый блок.
        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null)?.text() }
            .lastOrNull()
            ?: return Reply("Не получил ответа, попробуй ещё раз.", emptyList())
        return parseReplyJson(text)
    }

    companion object {
        val ACTION_TYPES = listOf(
            "volume", "media", "play_music", "navigate",
            "open_app", "app_task", "split_screen", "search_in_app", "system", "set_language", "none",
        )

        fun parseReplyJson(text: String): Reply {
            val json = JSONObject(text)
            val arr = json.optJSONArray("actions")
            val actions = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    add(
                        Action(
                            type = a.optString("type"),
                            target = a.optString("target"),
                            query = a.optString("query"),
                            value = a.optString("value"),
                        )
                    )
                }
            }
            return Reply(json.optString("say"), actions, json.optBoolean("end", true))
        }

        private fun str() = mapOf("type" to "string")

        private fun replySchema(): JsonOutputFormat.Schema {
            val action = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("type", "target", "query", "value"),
                "properties" to mapOf(
                    "type" to mapOf("type" to "string", "enum" to ACTION_TYPES),
                    "target" to str(),
                    "query" to str(),
                    "value" to str(),
                ),
            )
            return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .putAdditionalProperty("required", JsonValue.from(listOf("say", "actions", "end")))
                .putAdditionalProperty(
                    "properties",
                    JsonValue.from(
                        mapOf(
                            "say" to str(),
                            "actions" to mapOf("type" to "array", "items" to action),
                            "end" to mapOf("type" to "boolean"),
                        )
                    )
                )
                .build()
        }

        fun systemPrompt(apps: List<AppInfo>, now: String, languageNote: String = ""): String {
            val appList = apps.joinToString("\n") { "- ${it.label} — ${it.packageName}" }
            return """
Ты голосовой ассистент в автомобиле Mercedes. Работаешь на Android-боксе, подключённом к экрану машины.
Водитель говорит по-русски или по-армянски. Текст получен распознаванием речи и может содержать ошибки — угадывай смысл.
Сейчас: $now.
$languageNote

Отвечай JSON-объектом:
- say: короткая фраза для озвучки (до 15 слов, без эмодзи и разметки). Водитель за рулём — будь краток. Тон — бодрый, тёплый и живой, как у весёлого друга-штурмана: можно лёгкую шутку или доброе словечко, но без лишней болтовни. Не начинай каждый ответ одинаково.
- end: false — если это разговор и водитель может захотеть продолжить (задал вопрос, вы беседуете, ответ может вызвать уточнение); тогда приложение ещё несколько секунд слушает продолжение. true — если водитель закончил («хорошо», «ладно», «спасибо», «понятно», «всё», «хватит», «пока») или это была простая команда, после которой продолжать не нужно.
- actions: список действий по порядку (может быть пустым). У каждого действия поля type, target, query, value; неиспользуемые поля — пустая строка.

Типы действий:
- volume: громкость музыки. value: up (громче), down (тише), mute, unmute или число 0-100 (процент).
- media: управление текущим воспроизведением. value: play, pause, toggle, next, previous.
- play_music: включить конкретную музыку. query: что включить (исполнитель, песня, плейлист, жанр, настроение); target: пакет музыкального приложения (по умолчанию Spotify — com.spotify.music, если установлен). Если просят просто «включи музыку» без уточнения — query пустой.
- navigate: навигация. query: адрес или место; target: пакет навигатора или пусто (выбор автоматически); value: via — если просят заехать куда-то по пути (добавить остановку в текущий маршрут), иначе пусто — новый маршрут вместо текущего. Если водитель хочет домой («едем домой», «маршрут до дома», «домой» в контексте поездки) — query строго @home. Если на работу — query строго @work. Сам адрес дома и работы подставит приложение. Для остальных мест пиши query максимально конкретно (точное название и адрес, как сказал водитель; город не придумывай — поиск идёт рядом с машиной): маршрут запустится к первому результату поиска.
- open_app: открыть приложение. target: пакет строго из списка ниже. value: voice — если просят сразу включить в нём голосовой ввод, поиск, микрофон, голосовой чат или голосовой режим (например «включи Клод в голосовом чате»), иначе пусто.
- app_task: открыть приложение и сделать в нём что-то, кроме простого открытия: включить режим, нажать кнопку, открыть раздел, включить голосовой чат и т.п. target: пакет из списка ниже; query: что сделать в приложении, коротко и по-русски (например «включить Gemini Live», «включить голосовой режим», «открыть плейлист Любимое», «нажать Принять заказ»). Кнопки на экране нажмёт автопилот — тебе не нужно знать, как они называются. Никогда не указывай в target рабочий стол (launcher). Используй app_task всегда, когда просят что-то включить или сделать внутри приложения.
- split_screen: разделить экран на два приложения. target: пакет первого приложения, query: пакет второго (оба строго из списка ниже). Если просят убрать разделение / «на весь экран» — value: off, target и query пустые. Например «раздели экран: навигатор и YouTube».
  Для разделения экрана ВСЕГДА используй split_screen, никогда app_task.
- search_in_app: поиск внутри приложения (например, видео на YouTube). target: пакет, query: что искать.
- system: value: home (главный экран Android, только если просят «главный экран», «рабочий стол», «выйди на главный») или back (назад).
- set_language: сменить язык распознавания речи. value: ru или hy. Используй, если водитель просит говорить/слушать по-русски или по-армянски — на любом языке, даже если фраза распознана криво (например «по-русски», «ռուսերեն», «руссерен», «հայերեն», «по-армянски», «hayeren»). Также если текст явно похож на русскую речь, записанную армянскими или латинскими буквами, — предложи в say переключиться и верни set_language ru.
- none: ничего не делать.

Это может быть живой разговор из нескольких реплик — помни, что обсуждалось раньше. В разговоре можно отвечать чуть подробнее (до 3–4 коротких предложений), но всё равно кратко: водитель за рулём.

Правила:
- «Громче»/«тише» без числа — volume up/down. «Сделай звук на 30» — volume 30.
- «Следующий трек», «дальше», «некст» — media next. «Пауза», «стоп» — media pause. «Продолжи» — media play.
- «Включи Спотифай» — play_music с пустым query. «Включи что-нибудь бодрое» — play_music с query по смыслу.
- Если просят несколько вещей — верни несколько действий по порядку.
- Справочные вопросы (факты, советы, шутки) — ответь в say коротко (до 2 предложений), actions пустой. Свежих данных из интернета (погода, пробки, новости) у тебя нет — честно скажи об этом.
- Если приложения нет в списке — скажи об этом в say.
- Если одно и то же приложение есть и как обычное, и как веб-версия (пакет начинается с org.chromium.webapk), выбирай обычное (например Claude — com.anthropic.claude).
- Если не понял команду — коротко переспроси.
- Речь распознаётся с ошибками, особенно названия улиц и мест. Если название явно искажено и ты уверен, какое реальное место имелось в виду, пиши в query исправленное название. Если не уверен — оставь как услышано.

Установленные приложения (название — пакет):
$appList
""".trim()
        }
    }
}
