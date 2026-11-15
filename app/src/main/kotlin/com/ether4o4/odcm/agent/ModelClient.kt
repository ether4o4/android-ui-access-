package com.ether4o4.odcm.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock

/** One decision from the model: a tool name plus its parsed arguments, with the raw blocks to echo back. */
class ModelDecision(
    val toolUseId: String?,
    val toolName: String?,
    val toolArgs: Map<String, Any?>,
    val assistantBlocks: List<ContentBlockParam>,
    val plainText: String,
)

/** Thin wrapper over the Anthropic Java SDK. Keeps the running message history for one task. */
class ModelClient(
    apiKey: String,
    private val model: String,
    private val effort: String,
    private val systemPrompt: String,
) {
    private val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    private val history = ArrayList<MessageParam>()

    fun addUserText(text: String) {
        history.add(MessageParam.ofUserOfContentBlockParams(listOf(
            ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()),
        )))
    }

    fun addToolResult(toolUseId: String, result: String, isError: Boolean) {
        history.add(MessageParam.ofUserOfContentBlockParams(listOf(
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder().toolUseId(toolUseId).content(result).isError(isError).build(),
            ),
        )))
    }

    fun next(): ModelDecision {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .outputConfig(OutputConfig.builder().effort(effortValue()).build())
            .systemOfTextBlockParams(listOf(
                TextBlockParam.builder()
                    .text(systemPrompt)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build(),
            ))
            .messages(history)
        for (tool in Tools.all) builder.addTool(tool)
        val params = builder.build()

        val response = client.messages().create(params)
        history.add(response.toParam())

        var toolUse: ToolUseBlock? = null
        val text = StringBuilder()
        val blocks = ArrayList<ContentBlockParam>()
        for (block in response.content()) {
            block.text().ifPresent { text.append(it.text()) }
            block.toolUse().ifPresent { if (toolUse == null) toolUse = it }
        }
        return ModelDecision(
            toolUseId = toolUse?.id(),
            toolName = toolUse?.name(),
            toolArgs = toolUse?.let { argsOf(it) } ?: emptyMap(),
            assistantBlocks = blocks,
            plainText = text.toString().trim(),
        )
    }

    private fun argsOf(block: ToolUseBlock): Map<String, Any?> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            block._input().convert(Map::class.java) as? Map<String, Any?>
        }.getOrNull() ?: emptyMap()

    private fun effortValue(): OutputConfig.Effort = when (effort.lowercase()) {
        "high" -> OutputConfig.Effort.HIGH
        "medium" -> OutputConfig.Effort.MEDIUM
        else -> OutputConfig.Effort.LOW
    }

    @Suppress("unused")
    private fun modelConstant() = Model.of(model)
}
