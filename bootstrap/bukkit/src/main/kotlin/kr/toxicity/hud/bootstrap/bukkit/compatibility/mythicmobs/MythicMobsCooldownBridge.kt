package kr.toxicity.hud.bootstrap.bukkit.compatibility.mythicmobs

import org.bukkit.entity.Player

import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Locale
import java.util.Optional
import java.util.logging.Logger

/**
 * Reads MythicMobs skill cooldowns for MMOItems abilities that only define `skillcooldown`.
 *
 * Mirrors the bridge used by FluffyCastingHud and is dual-compatible with
 * MythicMobs 5.12 (`PlaceholderDouble`) and 5.13 (`PlaceholderDuration`).
 */
object MythicMobsCooldownBridge {

    private const val MYTHIC_BUKKIT = "io.lumine.mythic.bukkit.MythicBukkit"

    private var state = InitState.UNINITIALIZED
    private var apiVariant = "unknown"

    fun isAvailable(): Boolean {
        ensureInitialized()
        return state == InitState.AVAILABLE
    }

    /** `5.12`, `5.13`, or `unknown` after initialization. */
    fun getApiVariant(): String {
        ensureInitialized()
        return apiVariant
    }

    /** Clears cached reflection, e.g. after a plugin reload. */
    @Synchronized
    fun reset() {
        state = InitState.UNINITIALIZED
        apiVariant = "unknown"
        Holder.SKILL_MANAGER = null
        Holder.GET_SKILL = null
        Holder.GET_CASTER_ENTITY = null
        Holder.GET_CASTER_ABSTRACT = null
        Holder.BUKKIT_ADAPTER = null
        Holder.GET_COOLDOWN = null
        Holder.GET_COOLDOWN_MILLIS = null
        Holder.GET_CONFIGURED_COOLDOWN = null
        Holder.PLACEHOLDER_GET_META = null
        Holder.PLACEHOLDER_GET = null
        Holder.DURATION_GET_SECONDS = null
        Holder.DURATION_GET_MILLIS = null
        Holder.CASTER_REMAINING_SECONDS = null
        Holder.CASTER_REMAINING_MILLIS = null
    }

    /**
     * True when the Mythic skill YAML declares a `Cooldown:` entry.
     */
    fun hasConfiguredCooldown(mythicSkill: Any?): Boolean {
        ensureInitialized()
        if (state != InitState.AVAILABLE || mythicSkill == null || Holder.GET_CONFIGURED_COOLDOWN == null) {
            return false
        }
        return runCatching {
            Holder.GET_CONFIGURED_COOLDOWN!!.invoke(mythicSkill) != null
        }.getOrDefault(false)
    }

    /**
     * Evaluates the configured `Cooldown:` placeholder (not the remaining time).
     */
    fun getConfiguredCooldownSeconds(mythicSkill: Any?, skillMetadata: Any?): Double {
        ensureInitialized()
        if (state != InitState.AVAILABLE || mythicSkill == null || Holder.GET_CONFIGURED_COOLDOWN == null) {
            return 0.0
        }
        return runCatching {
            val placeholder = Holder.GET_CONFIGURED_COOLDOWN!!.invoke(mythicSkill) ?: return 0.0
            val value = resolvePlaceholder(placeholder, skillMetadata)
            toSeconds(value)
        }.getOrDefault(0.0)
    }

    fun isOnCooldown(player: Player, skillId: String): Boolean {
        return getRemainingCooldown(player, skillId) > 0
    }

    fun getRemainingCooldown(player: Player?, skillId: String?): Double {
        ensureInitialized()
        if (state != InitState.AVAILABLE || player == null || skillId.isNullOrBlank()) {
            return 0.0
        }
        return runCatching {
            val caster = getCaster(player) ?: return 0.0
            var longest = 0.0
            for (mythicSkillName in mythicSkillCandidates(skillId)) {
                val skill = resolveSkill(mythicSkillName) ?: continue
                val remaining = readRemainingCooldown(skill, caster)
                if (remaining > longest) longest = remaining
            }
            longest
        }.getOrDefault(0.0)
    }

    /** Remaining cooldown on a specific Mythic skill instance. */
    fun getRemainingCooldown(player: Player?, mythicSkill: Any?): Double {
        ensureInitialized()
        if (state != InitState.AVAILABLE || player == null || mythicSkill == null) {
            return 0.0
        }
        return runCatching {
            val caster = getCaster(player) ?: return 0.0
            readRemainingCooldown(mythicSkill, caster)
        }.getOrDefault(0.0)
    }

    fun mythicSkillCandidates(skillId: String): List<String> {
        val titled = toMythicSkillName(skillId)
        val candidates = ArrayList<String>(10)
        candidates.add(titled + "_SKILL")
        candidates.add(titled + "_Skill")
        candidates.add(titled + "_CAST")
        candidates.add(titled + "_Cast")
        candidates.add(titled)
        if (!skillId.equals(titled, ignoreCase = true)) {
            candidates.add(skillId + "_SKILL")
            candidates.add(skillId + "_Skill")
            candidates.add(skillId + "_CAST")
            candidates.add(skillId)
        }
        return candidates
    }

    private fun toMythicSkillName(skillId: String): String {
        val parts = skillId.lowercase(Locale.ROOT).split("_")
        val builder = StringBuilder()
        for (index in parts.indices) {
            if (index > 0) builder.append('_')
            val part = parts[index]
            if (part.isEmpty()) continue
            builder.append(Character.toUpperCase(part[0]))
            if (part.length > 1) builder.append(part.substring(1))
        }
        return builder.toString()
    }

    private fun getCaster(player: Player): Any? {
        Holder.GET_CASTER_ENTITY?.let { method ->
            method.invoke(Holder.SKILL_MANAGER, player)?.let { return it }
        }
        val abstractEntity = Holder.BUKKIT_ADAPTER?.invoke(null, player) ?: return null
        return Holder.GET_CASTER_ABSTRACT?.invoke(Holder.SKILL_MANAGER, abstractEntity)
    }

    private fun resolveSkill(mythicSkillName: String): Any? {
        val optional = Holder.GET_SKILL?.invoke(Holder.SKILL_MANAGER, mythicSkillName) ?: return null
        return (optional as? Optional<*>)?.orElse(null)
    }

    private fun resolvePlaceholder(placeholder: Any, skillMetadata: Any?): Any? {
        if (skillMetadata != null && Holder.PLACEHOLDER_GET_META != null) {
            runCatching {
                return Holder.PLACEHOLDER_GET_META!!.invoke(placeholder, skillMetadata)
            }
        }
        Holder.PLACEHOLDER_GET?.let {
            runCatching {
                return it.invoke(placeholder)
            }
        }
        val type = placeholder.javaClass
        if (skillMetadata != null) {
            findCompatibleGet(type, skillMetadata.javaClass)?.let {
                return it.invoke(placeholder, skillMetadata)
            }
        }
        return runCatching {
            type.getMethod("get").invoke(placeholder)
        }.getOrNull()
    }

    private fun findCompatibleGet(type: Class<*>, metaType: Class<*>): Method? {
        return type.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(metaType)
        }
    }

    private fun toSeconds(value: Any?): Double {
        if (value == null) return 0.0
        if (value is Number) return value.toDouble().coerceAtLeast(0.0)
        Holder.DURATION_GET_SECONDS?.let {
            if (it.declaringClass.isInstance(value)) {
                val seconds = it.invoke(value)
                if (seconds is Number) return seconds.toDouble().coerceAtLeast(0.0)
            }
        }
        Holder.DURATION_GET_MILLIS?.let {
            if (it.declaringClass.isInstance(value)) {
                val millis = it.invoke(value)
                if (millis is Number) return millis.toDouble().coerceAtLeast(0.0) / 1000.0
            }
        }
        runCatching {
            val seconds = value.javaClass.getMethod("getSeconds").invoke(value)
            if (seconds is Number) return seconds.toDouble().coerceAtLeast(0.0)
        }
        runCatching {
            val millis = value.javaClass.getMethod("getMilliseconds").invoke(value)
            if (millis is Number) return millis.toDouble().coerceAtLeast(0.0) / 1000.0
        }
        return 0.0
    }

    private fun readRemainingCooldown(skill: Any, caster: Any): Double {
        Holder.CASTER_REMAINING_SECONDS?.let {
            val remaining = it.invoke(caster, skill)
            if (remaining is Number) {
                val seconds = remaining.toDouble().coerceAtLeast(0.0)
                if (seconds >= 0.5) return seconds
            }
        }
        Holder.CASTER_REMAINING_MILLIS?.let {
            val remaining = it.invoke(caster, skill)
            if (remaining is Number) {
                val seconds = remaining.toDouble().coerceAtLeast(0.0) / 1000.0
                if (seconds >= 0.5) return seconds
            }
        }
        Holder.GET_COOLDOWN?.let {
            val remaining = it.invoke(skill, caster)
            if (remaining is Number) {
                val seconds = remaining.toDouble().coerceAtLeast(0.0)
                if (seconds >= 0.5) return seconds
            }
        }
        Holder.GET_COOLDOWN_MILLIS?.let {
            val remaining = it.invoke(skill, caster)
            if (remaining is Number) {
                val seconds = remaining.toLong().coerceAtLeast(0L) / 1000.0
                if (seconds >= 0.5) return seconds
            }
        }
        return 0.0
    }

    private fun ensureInitialized() {
        if (state != InitState.UNINITIALIZED) return
        synchronized(this) {
            if (state != InitState.UNINITIALIZED) return
            state = initialize()
        }
    }

    private fun initialize(): InitState {
        return runCatching {
            val mythicBukkitClass = Class.forName(MYTHIC_BUKKIT)
            val mythicPlugin = mythicBukkitClass.getMethod("inst").invoke(null)
            val skillManager = mythicPlugin.javaClass.getMethod("getSkillManager").invoke(mythicPlugin)

            val getSkill = skillManager.javaClass.getMethod("getSkill", String::class.java)
            val skillCasterClass = Class.forName("io.lumine.mythic.api.skills.SkillCaster")
            val skillClass = Class.forName("io.lumine.mythic.api.skills.Skill")
            val abstractSkillClass = Class.forName("io.lumine.mythic.core.skills.AbstractSkill")

            val getCooldown = abstractSkillClass.methods.firstOrNull {
                it.name == "getCooldown" && it.parameterCount == 1 && it.parameterTypes[0] == skillCasterClass
            }
            val getCooldownMillis = abstractSkillClass.methods.firstOrNull {
                it.name == "getCooldownMillis" && it.parameterCount == 1 && it.parameterTypes[0] == skillCasterClass
            }

            var getConfiguredCooldown: Method? = null
            var placeholderGetMeta: Method? = null
            var placeholderGet: Method? = null
            var durationGetSeconds: Method? = null
            var durationGetMillis: Method? = null
            var detectedVariant = "5.12"
            runCatching {
                getConfiguredCooldown = abstractSkillClass.getMethod("getCooldown")
                val placeholderType = getConfiguredCooldown!!.returnType
                val placeholderMetaClass = Class.forName(
                    "io.lumine.mythic.core.skills.placeholders.PlaceholderMeta"
                )
                if (placeholderType.name.endsWith("PlaceholderDuration")) {
                    detectedVariant = "5.13"
                    placeholderGetMeta = findMethod(placeholderType, "get", placeholderMetaClass)
                    placeholderGet = findNoArgGet(placeholderType)
                    runCatching {
                        val durationClass = Class.forName("io.lumine.mythic.bukkit.utils.numbers.Duration")
                        durationGetSeconds = durationClass.getMethod("getSeconds")
                        durationGetMillis = durationClass.getMethod("getMilliseconds")
                    }
                } else {
                    detectedVariant = "5.12"
                    placeholderGetMeta = findMethod(placeholderType, "get", placeholderMetaClass)
                    placeholderGet = findNoArgGet(placeholderType)
                }
            }

            var casterRemainingSeconds = findMethod(
                skillCasterClass,
                "getSkillCooldownRemainingSeconds",
                abstractSkillClass
            ) ?: findMethod(skillCasterClass, "getSkillCooldownRemainingSeconds", skillClass)
            var casterRemainingMillis = findMethod(
                skillCasterClass,
                "getSkillCooldownRemainingMillis",
                abstractSkillClass
            ) ?: findMethod(skillCasterClass, "getSkillCooldownRemainingMillis", skillClass)

            if (casterRemainingSeconds != null) {
                // Found through AbstractSkill/Skill overload.
            }

            var getCasterEntity: Method? = null
            var getCasterAbstract: Method? = null
            for (method in skillManager.javaClass.methods) {
                if (method.name != "getCaster" || method.parameterCount != 1) continue
                val parameter = method.parameterTypes[0]
                if (org.bukkit.entity.Entity::class.java.isAssignableFrom(parameter)) {
                    getCasterEntity = method
                } else if (parameter.name.contains("AbstractEntity")) {
                    getCasterAbstract = method
                }
            }
            if (getCasterEntity == null && getCasterAbstract == null) {
                return InitState.UNAVAILABLE
            }

            val bukkitAdapter = runCatching {
                Class.forName("io.lumine.mythic.bukkit.BukkitAdapter")
                    .getMethod("adapt", org.bukkit.entity.Entity::class.java)
            }.getOrNull()

            Holder.SKILL_MANAGER = skillManager
            Holder.GET_SKILL = getSkill
            Holder.GET_CASTER_ENTITY = getCasterEntity
            Holder.GET_CASTER_ABSTRACT = getCasterAbstract
            Holder.BUKKIT_ADAPTER = bukkitAdapter
            Holder.GET_COOLDOWN = getCooldown
            Holder.GET_COOLDOWN_MILLIS = getCooldownMillis
            Holder.GET_CONFIGURED_COOLDOWN = getConfiguredCooldown
            Holder.PLACEHOLDER_GET_META = placeholderGetMeta
            Holder.PLACEHOLDER_GET = placeholderGet
            Holder.DURATION_GET_SECONDS = durationGetSeconds
            Holder.DURATION_GET_MILLIS = durationGetMillis
            Holder.CASTER_REMAINING_SECONDS = casterRemainingSeconds
            Holder.CASTER_REMAINING_MILLIS = casterRemainingMillis
            apiVariant = detectedVariant
            Logger.getLogger("BetterHud").info("MythicMobs cooldown bridge ready (API $detectedVariant)")
            InitState.AVAILABLE
        }.getOrDefault(InitState.UNAVAILABLE)
    }

    private fun findMethod(type: Class<*>, name: String, parameter: Class<*>): Method? {
        return runCatching { type.getMethod(name, parameter) }.getOrNull()
    }

    private fun findNoArgGet(type: Class<*>): Method? {
        return runCatching { type.getMethod("get") }.getOrNull()
    }

    private enum class InitState {
        UNINITIALIZED,
        AVAILABLE,
        UNAVAILABLE,
    }

    private object Holder {
        var SKILL_MANAGER: Any? = null
        var GET_SKILL: Method? = null
        var GET_CASTER_ENTITY: Method? = null
        var GET_CASTER_ABSTRACT: Method? = null
        var BUKKIT_ADAPTER: Method? = null
        var GET_COOLDOWN: Method? = null
        var GET_COOLDOWN_MILLIS: Method? = null
        var GET_CONFIGURED_COOLDOWN: Method? = null
        var PLACEHOLDER_GET_META: Method? = null
        var PLACEHOLDER_GET: Method? = null
        var DURATION_GET_SECONDS: Method? = null
        var DURATION_GET_MILLIS: Method? = null
        var CASTER_REMAINING_SECONDS: Method? = null
        var CASTER_REMAINING_MILLIS: Method? = null
    }
}
