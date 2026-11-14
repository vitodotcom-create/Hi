package com.carvoice.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
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

/** Ответ ассистента: фраза для озвучки + список действий. */
data class Reply(val say: String, val actions: List<Action>)

/**
 * «Мозг» ассистента: отправляет распознанную фразу в Claude и получает
 * строго структурированный JSON с ответом и действиями.
 */
class ClaudeBrain(apiKey: String, private val model: String) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(40))
        .maxRetries(1)
        .build()

    fun ask(userText: String, apps: List<AppInfo>, now: String): Reply {
        val message = try {
            client.messages().create(buildParams(userText, apps, now, withFallbacks = supportsFallbacks()))
        } catch (e: BadRequestException) {
            // Если сервер не принял параметр fallbacks — повторяем без него.
            if (!supportsFallbacks()) throw e
            client.messages().create(buildParams(userText, apps, now, withFallbacks = false))
        }
        return parse(message)
    }

    private fun supportsFallbacks(): Boolean =
        model.startsWith("claude-opus-5-5") || model.startsWith("claude-sonnet-5-5") ||
            model.startsWith("claude-fable-5")

    private fun buildParams(
        userText: String,
        apps: List<AppInfo>,
        now: String,
        withFallbacks: Boolean,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(systemPrompt(apps, now))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(JsonOutputFormat.builder().schema(replySchema()).build())
                    .build()
            )
            .addUserMessage(userText)
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
            "open_app", "search_in_app", "system", "none",
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
            return Reply(json.optString("say"), actions)
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
                .putAdditionalProperty("required", JsonValue.from(listOf("say", "actions")))
                .putAdditionalProperty(
                    "properties",
                    JsonValue.from(
                        mapOf(
                            "say" to str(),
                            "actions" to mapOf("type" to "array", "items" to action),
                        )
                    )
                )
                .build()
        }

        fun systemPrompt(apps: List<AppInfo>, now: String): String {
            val appList = apps.joinToString("\n") { "- ${it.label} — ${it.packageName}" }
            return """
Ты голосовой ассистент в автомобиле Mercedes. Работаешь на Android-боксе, подключённом к экрану машины.
Водитель говорит по-русски. Текст получен распознаванием речи и может содержать ошибки — угадывай смысл.
Сейчас: $now.

Отвечай JSON-объектом:
- say: короткая фраза для озвучки (до 15 слов, без эмодзи и разметки). Водитель за рулём — будь краток и дружелюбен.
- actions: список действий по порядку (может быть пустым). У каждого действия поля type, target, query, value; неиспользуемые поля — пустая строка.

Типы действий:
- volume: громкость музыки. value: up (громче), down (тише), mute, unmute или число 0-100 (процент).
- media: управление текущим воспроизведением. value: play, pause, toggle, next, previous.
- play_music: включить конкретную музыку. query: что включить (исполнитель, песня, плейлист, жанр, настроение); target: пакет музыкального приложения (по умолчанию Spotify — com.spotify.music, если установлен). Если просят просто «включи музыку» без уточнения — query пустой.
- navigate: навигация. query: адрес или место; target: пакет навигатора или пусто (выбор автоматически).
- open_app: открыть приложение. target: пакет строго из списка ниже. value: voice — если просят сразу включить в нём голосовой ввод/поиск/микрофон, иначе пусто.
- search_in_app: поиск внутри приложения (например, видео на YouTube). target: пакет, query: что искать.
- system: value: home (главный экран) или back (назад).
- none: ничего не делать.

Правила:
- «Громче»/«тише» без числа — volume up/down. «Сделай звук на 30» — volume 30.
- «Следующий трек», «дальше», «некст» — media next. «Пауза», «стоп» — media pause. «Продолжи» — media play.
- «Включи Спотифай» — play_music с пустым query. «Включи что-нибудь бодрое» — play_music с query по смыслу.
- Если просят несколько вещей — верни несколько действий по порядку.
- Справочные вопросы (факты, советы, шутки) — ответь в say коротко (до 2 предложений), actions пустой. Свежих данных из интернета (погода, пробки, новости) у тебя нет — честно скажи об этом.
- Если приложения нет в списке — скажи об этом в say.
- Если не понял команду — коротко переспроси.

Установленные приложения (название — пакет):
$appList
""".trim()
        }
    }
}
