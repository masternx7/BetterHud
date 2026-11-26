package kr.toxicity.hud.skill

import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.plugin.ReloadInfo
import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.configuration.PluginConfiguration
import kr.toxicity.hud.manager.BetterHudManager
import kr.toxicity.hud.resource.GlobalResource
import kr.toxicity.hud.util.forEachSubConfiguration
import kr.toxicity.hud.util.handleFailure
import kr.toxicity.hud.util.info
import kr.toxicity.hud.util.toTextColor
import kr.toxicity.hud.util.warn
import net.kyori.adventure.text.format.TextColor
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Reads `skill-hud.yml` and connects the HUD/layout renderers with an [SkillHudProvider].
 */
object SkillHudManager : BetterHudManager {

    override val managerName: String = "SkillHud"
    override val supportExternalPacks: Boolean = false

    private var enabled = true
    private var checkClassRestriction = true
    private var checkLevelRestriction = true
    private var requireItemSkill = true
    private var autoFromItem = false
    private var hideWhenNoMatch = true
    private var defaultHud = "default"
    private var digitEnabled = true
    private var configuredDigitSize = 8
    private var configuredDigitFont = ""
    private var configuredDigitColor: TextColor = TextColor.color(0xFFFFFF)
    private var readyColor: TextColor = TextColor.color(0xFFFFFF)
    private var cooldownColor: TextColor = TextColor.color(0x8A8A8A)

    private var profiles = LinkedHashMap<String, SkillProfile>()
    private var profileHuds = HashSet<String>()
    private val layoutMaxCooldowns = ConcurrentHashMap<String, Double>()

    private val cache = ConcurrentHashMap<UUID, Snapshot>()

    val isEnabled: Boolean
        get() = enabled

    val isRequireItemSkill: Boolean
        get() = requireItemSkill

    val isDigitEnabled: Boolean
        get() = enabled && digitEnabled

    val digitSize: Int
        get() = configuredDigitSize

    val digitFont: String
        get() = configuredDigitFont

    val digitColor: TextColor
        get() = configuredDigitColor

    val readyColorOrNull: TextColor?
        get() = readyColor

    val cooldownColorOrNull: TextColor?
        get() = cooldownColor

    override fun start() {
    }

    override fun preReload() {
        cache.clear()
        layoutMaxCooldowns.clear()
    }

    override fun reload(workingDirectory: File, info: ReloadInfo, resource: GlobalResource) {
        runCatching {
            load(PluginConfiguration.SKILL_HUD.create())
        }.handleFailure(info) {
            "Unable to load skill-hud.yml"
        }
    }

    override fun postReload() {
        cache.clear()
    }

    override fun end() {
        cache.clear()
    }

    private fun load(yaml: YamlObject) {
        enabled = yaml.getAsBoolean("enabled", true)
        checkClassRestriction = yaml.getAsBoolean("check-class-restriction", true)
        checkLevelRestriction = yaml.getAsBoolean("check-level-restriction", true)
        requireItemSkill = yaml.getAsBoolean("require-item-skill", true)
        autoFromItem = yaml.getAsBoolean("auto-from-item", false)
        hideWhenNoMatch = yaml.getAsBoolean("hide-when-no-match", true)
        defaultHud = yaml.getAsString("default-hud", "default")
        readyColor = yaml["ready-color"]?.asString()?.toTextColor() ?: TextColor.color(0xFFFFFF)
        cooldownColor = yaml["cooldown-color"]?.asString()?.toTextColor() ?: TextColor.color(0x8A8A8A)

        val digits = yaml["digits"]?.asObject()
        digitEnabled = digits?.getAsBoolean("enabled", true) ?: true
        configuredDigitSize = (digits?.getAsInt("size", 8) ?: 8).coerceIn(4, 64)
        configuredDigitFont = digits?.getAsString("font", "") ?: ""
        configuredDigitColor = digits?.get("color")?.asString()?.toTextColor() ?: TextColor.color(0xFFFFFF)

        val newProfiles = LinkedHashMap<String, SkillProfile>()
        val newProfileHuds = HashSet<String>()
        yaml["profiles"]?.asObject()?.forEachSubConfiguration { key, section ->
            val type = section.getAsString("type", "").trim()
            val id = section.getAsString("id", "").trim()
            if (type.isEmpty() || id.isEmpty()) {
                warn("skill-hud.yml: profile '$key' is missing type/id.")
                return@forEachSubConfiguration
            }
            val hud = section.getAsString("hud", defaultHud).trim().ifEmpty { defaultHud }
            val slots = LinkedHashMap<String, Double>()
            section["slots"]?.asArray()?.forEach { element ->
                val slot = element.asObject()
                val skill = slot["skill"]?.asString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                slots[skill.uppercase(Locale.ROOT)] = slot.getAsDouble("max-cooldown", 0.0)
            }
            newProfiles[key] = SkillProfile(
                key = key,
                type = type.uppercase(Locale.ROOT),
                id = id.uppercase(Locale.ROOT),
                hudId = hud,
                slots = slots,
            )
            newProfileHuds += hud.lowercase(Locale.ROOT)
        }
        profiles = newProfiles
        profileHuds = newProfileHuds
        cache.clear()
        info("Loaded skill-hud.yml (profiles=${newProfiles.size}, digits=$digitEnabled).")
    }

    /**
     * Resolves the held item/profile for this player once per HUD tick.
     */
    fun snapshot(player: HudPlayer): Snapshot {
        if (!enabled) return Snapshot(player.tick, null, null)
        val provider = SkillHudProviders.provider ?: return Snapshot(player.tick, null, null)
        val cached = cache[player.uuid()]
        if (cached != null && cached.tick == player.tick) return cached

        val item = runCatching {
            provider.resolve(player)
        }.getOrNull()
        val profile = item?.let { target ->
            profiles.values.firstOrNull {
                it.matches(target.type, target.id)
            }
        }
        return Snapshot(player.tick, item, profile).also {
            cache[player.uuid()] = it
        }
    }

    fun item(player: HudPlayer): SkillHudItem? = snapshot(player).item

    /**
     * Returns whether the current HUD should be displayed for this player.
     */
    fun shouldShowHud(hudId: String, player: HudPlayer): Boolean {
        if (!enabled || profiles.isEmpty() || SkillHudProviders.provider == null) return true

        val snapshot = snapshot(player)
        val profile = snapshot.profile
        if (profile != null) {
            val item = snapshot.item
            if (checkClassRestriction && item?.classMismatch == true) return false
            if (checkLevelRestriction && item?.levelMismatch == true) return false
            if (hudId.equals(profile.hudId, ignoreCase = true)) return true
            return !profileHuds.contains(hudId.lowercase(Locale.ROOT))
        }

        if (autoFromItem && snapshot.item?.skills?.isNotEmpty() == true) {
            if (hudId.equals(defaultHud, ignoreCase = true)) return true
            return !profileHuds.contains(hudId.lowercase(Locale.ROOT))
        }

        return if (hideWhenNoMatch) {
            !profileHuds.contains(hudId.lowercase(Locale.ROOT))
        } else {
            true
        }
    }

    /**
     * Checks the `require-item-skill` rule for an image/layout element.
     */
    fun canRenderSkill(player: HudPlayer, skillId: String): Boolean {
        if (!enabled || SkillHudProviders.provider == null) return true
        val item = snapshot(player).item ?: return !requireItemSkill
        if (!requireItemSkill) return true
        // An item tier without that ability shows the lock image instead.
        if (item.skills.isEmpty()) return false
        val requested = normalizeSkill(skillId)
        return item.skills.any { normalizeSkill(it) == requested }
    }

    fun getRemainingCooldown(player: HudPlayer, skillId: String): Double {
        if (!enabled) return 0.0
        val provider = SkillHudProviders.provider ?: return 0.0
        val snapshot = snapshot(player)
        val actual = actualSkill(snapshot.item, skillId) ?: skillId
        return snapshot.cooldown(player, provider, actual)
    }

    fun getManaCost(player: HudPlayer, skillId: String): Double {
        val item = snapshot(player).item ?: return 0.0
        val actual = actualSkill(item, skillId) ?: return 0.0
        return item.manaCosts[actual] ?: 0.0
    }

    /**
     * Registers the `max-cooldown` value declared by a layout image.
     * This keeps `skill-hud.yml` profile slots optional.
     */
    fun registerMaxCooldown(skillId: String, seconds: Double) {
        if (seconds <= 0) return
        layoutMaxCooldowns[skillId.uppercase(Locale.ROOT)] = seconds
    }

    fun getMaxCooldown(player: HudPlayer, skillId: String): Double {
        if (!enabled) return 0.0
        val snapshot = snapshot(player)
        val fromProfile = snapshot.profile?.maxCooldown(skillId) ?: 0.0
        if (fromProfile > 0) return fromProfile
        val actual = actualSkill(snapshot.item, skillId) ?: skillId
        val fromItem = snapshot.item?.maxCooldowns?.get(actual.uppercase(Locale.ROOT)) ?: 0.0
        if (fromItem > 0) return fromItem
        val key = normalizeSkill(skillId)
        return layoutMaxCooldowns.entries.firstOrNull {
            normalizeSkill(it.key) == key
        }?.value ?: 0.0
    }

    private fun actualSkill(item: SkillHudItem?, requested: String): String? {
        if (item == null) return null
        val key = normalizeSkill(requested)
        return item.skills.firstOrNull { normalizeSkill(it) == key }
    }

    private fun normalizeSkill(value: String): String {
        var normalized = value.trim().uppercase(Locale.ROOT)
        if (normalized.endsWith("_ST")) normalized = normalized.dropLast(3)
        normalized = stripTier(normalized)
        return normalized.replace("_", "")
    }

    fun formatCooldown(seconds: Double): String {
        if (seconds <= 0) return ""
        return ceil(seconds).toInt().coerceIn(1, 99).toString()
    }

    /**
     * Removes an MMOItem tier suffix such as `_II`, `_III` or `_2`.
     * Used by `mmoitems_held_type_base` / `mmoitems_held_id_base`.
     */
    fun stripTier(value: String): String {
        return value.replace(Regex("(?i)_(?:I|II|III|IV|V|VI|VII|VIII|IX|X|[0-9]+)$"), "")
    }

    data class SkillProfile(
        val key: String,
        val type: String,
        val id: String,
        val hudId: String,
        val slots: Map<String, Double>,
    ) {
        fun matches(itemType: String, itemId: String): Boolean {
            return type.equals(itemType, ignoreCase = true) && id.equals(itemId, ignoreCase = true)
        }

        fun maxCooldown(skillId: String): Double {
            return slots[skillId.uppercase(Locale.ROOT)] ?: 0.0
        }
    }

    class Snapshot internal constructor(
        val tick: Long,
        val item: SkillHudItem?,
        val profile: SkillProfile?,
    ) {
        private val cooldowns = HashMap<String, Double>()

        fun cooldown(player: HudPlayer, provider: SkillHudProvider, skillId: String): Double {
            val key = skillId.uppercase(Locale.ROOT)
            synchronized(cooldowns) {
                return cooldowns.getOrPut(key) {
                    runCatching {
                        provider.remainingCooldown(player, skillId)
                    }.getOrDefault(0.0)
                }
            }
        }
    }
}
