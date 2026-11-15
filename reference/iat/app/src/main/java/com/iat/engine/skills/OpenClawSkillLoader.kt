package com.iat.engine.skills

import java.io.File
import org.yaml.snakeyaml.Yaml

data class OpenClawSkill(
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val mcpToolsRequired: List<String>,
    val triggerIntents: List<String>,
    val instructions: String,
    val filePath: String
)

class OpenClawSkillLoader {
    companion object {
        fun parseSkillFile(file: File): OpenClawSkill? {
            if (!file.exists() || !file.name.endsWith(".md")) return null
            val content = file.readText()
            val regex = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(content) ?: return null

            val yamlHeader = match.groupValues[1]
            val instructions = match.groupValues[2].trim()

            val yaml = Yaml()
            val data = try { yaml.load<Map<String, Any>>(yamlHeader) } catch (e: Exception) { null } ?: return null

            return OpenClawSkill(
                name = data["name"] as? String ?: file.nameWithoutExtension,
                author = data["author"] as? String ?: "Unknown",
                version = data["version"] as? String ?: "1.0.0",
                description = data["description"] as? String ?: "",
                mcpToolsRequired = (data["mcp_tools_required"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                triggerIntents = (data["trigger_intents"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                instructions = instructions,
                filePath = file.absolutePath
            )
        }
    }
}
