package com.agent.eae.android.core.tools

import com.agent.eae.android.data.EaeDatabase
import com.agent.eae.android.data.SkillEntity
import com.agent.eae.android.data.ToolEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skills: named procedures the agent saves once and reuses. Each saved skill also gets a
 * `skill:<name>` row in the tool catalogue, so it surfaces through `search_tools` exactly like a
 * built-in — the registry pattern is the extension point, not a parallel mechanism.
 */
class SkillStore(private val db: EaeDatabase) {

    suspend fun save(
        name: String,
        description: String,
        instructions: String,
        python: String?,
    ): String = withContext(Dispatchers.IO) {
        val slug = slugify(name)
        if (slug.isBlank()) return@withContext "Skill name must contain letters or digits."
        if (description.isBlank() || instructions.isBlank()) {
            return@withContext "A skill needs both a description and instructions."
        }
        db.skillDao().upsert(
            SkillEntity(
                name = slug,
                description = description,
                instructions = instructions,
                python = python?.takeIf { it.isNotBlank() },
                createdAt = System.currentTimeMillis(),
            ),
        )
        db.toolDao().upsertAll(listOf(catalogRow(slug, description)))
        "Saved skill \"$slug\". It is now discoverable via search_tools as skill:$slug."
    }

    suspend fun get(name: String): SkillEntity? = withContext(Dispatchers.IO) {
        db.skillDao().byName(slugify(name))
    }

    suspend fun all(): List<SkillEntity> = withContext(Dispatchers.IO) { db.skillDao().all() }

    suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val slug = slugify(name)
        db.toolDao().delete("skill:$slug")
        db.skillDao().delete(slug) > 0
    }

    private fun catalogRow(slug: String, description: String): ToolEntity {
        val schema = JSONObject()
            .put("name", "skill:$slug")
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "input",
                            JSONObject().put("type", "string").put("description", "Optional input for the skill."),
                        ),
                    )
                    .put("required", JSONArray()),
            )
        val keywords = (slug.split('-') + description.lowercase().split(Regex("[^a-z0-9]+")))
            .filter { it.length > 2 }
            .distinct()
            .take(12)
        return ToolEntity(
            name = "skill:$slug",
            description = "$description (saved skill)",
            keywords = (keywords + "skill").joinToString(","),
            schemaJson = schema.toString(),
            category = "skill",
        )
    }

    private fun slugify(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
}
