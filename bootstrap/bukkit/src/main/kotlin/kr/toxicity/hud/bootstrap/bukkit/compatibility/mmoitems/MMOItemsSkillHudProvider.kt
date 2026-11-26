package kr.toxicity.hud.bootstrap.bukkit.compatibility.mmoitems

import com.google.gson.JsonParser
import io.lumine.mythic.lib.api.item.NBTItem
import io.lumine.mythic.lib.api.player.MMOPlayerData
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.bootstrap.bukkit.compatibility.mythicmobs.MythicMobsCooldownBridge
import kr.toxicity.hud.bootstrap.bukkit.util.bukkitPlayer
import kr.toxicity.hud.skill.SkillHudItem
import kr.toxicity.hud.skill.SkillHudProvider
import net.Indyuce.mmoitems.ItemStats
import net.Indyuce.mmoitems.MMOItems
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem
import net.Indyuce.mmoitems.api.player.PlayerData
import net.Indyuce.mmoitems.stat.data.AbilityListData
import net.Indyuce.mmoitems.stat.data.RequiredLevelData
import net.Indyuce.mmoitems.stat.data.StringListData
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale

/**
 * MMOItems implementation of the skill HUD provider.
 *
 * Ability data is read from the MMOItems NBT tag directly. This keeps the HUD
 * working even if [LiveMMOItem] fails to load an unrelated item stat.
 */
class MMOItemsSkillHudProvider : SkillHudProvider {

    companion object {
        private const val ABILITY_TAG = "MMOITEMS_ABILITY"
        private const val ITEM_TYPE_TAG = "MMOITEMS_ITEM_TYPE"
        private const val ITEM_ID_TAG = "MMOITEMS_ITEM_ID"
    }

    override fun resolve(player: HudPlayer): SkillHudItem? {
        val bukkit = player.bukkitPlayer
        val item = bukkit.inventory.itemInMainHand
        if (item.type.isAir) return null

        val nbt = runCatching { NBTItem.get(item) }.getOrNull() ?: return null
        val type = runCatching { MMOItems.getTypeName(nbt) }.getOrNull()
            ?: runCatching { MMOItems.getType(nbt)?.id }.getOrNull()
            ?: runCatching { nbt.getString(ITEM_TYPE_TAG).takeIf { it.isNotBlank() } }.getOrNull()
            ?: return null
        val id = runCatching { MMOItems.getID(nbt) }.getOrNull()
            ?: runCatching { nbt.getString(ITEM_ID_TAG).takeIf { it.isNotBlank() } }.getOrNull()
            ?: return null

        val live = runCatching { LiveMMOItem(nbt) }.getOrNull()
        val data = MMOItems.plugin.playerDataManager.getOrNull(bukkit.uniqueId)
        val cooldowns = LinkedHashMap<String, Double>()
        val manaCosts = HashMap<String, Double>()
        readAbilities(nbt, live, data?.mmoPlayerData, cooldowns, manaCosts)

        val classMismatch = live != null &&
            hasClassRestriction(live) &&
            !matchesRequiredClass(bukkit, live)
        val requiredLevel = live?.let(::requiredLevel) ?: 0
        val playerLevel = playerLevel(bukkit)
        val levelMismatch = requiredLevel > 0 && playerLevel < requiredLevel

        return SkillHudItem(
            type = type.uppercase(Locale.ROOT),
            id = id.uppercase(Locale.ROOT),
            skills = cooldowns.keys,
            maxCooldowns = cooldowns,
            classMismatch = classMismatch,
            requiredLevel = requiredLevel,
            playerLevel = playerLevel,
            levelMismatch = levelMismatch,
            manaCosts = manaCosts,
        )
    }

    override fun remainingCooldown(player: HudPlayer, skillId: String): Double {
        val bukkit = player.bukkitPlayer
        val candidates = listOf(skillId, skillId.removeSuffix("_ST"))
        val data = runCatching { MMOPlayerData.get(bukkit) }.getOrNull()
        if (data != null) {
            var direct = 0.0
            for (candidate in candidates) {
                direct = maxOf(
                    direct,
                    data.cooldownMap.getCooldown("skill_${candidate.lowercase(Locale.ROOT)}"),
                    data.cooldownMap.getCooldown("skill_$candidate"),
                )
            }
            if (direct > 0) return direct
        }
        return candidates.maxOfOrNull {
            MythicMobsCooldownBridge.getRemainingCooldown(bukkit, it)
        } ?: 0.0
    }

    /**
     * Reads ability IDs, cooldowns and mana costs from `MMOITEMS_ABILITY`.
     * The JSON format is the same one shown by the MMOItems NBT editor.
     */
    private fun readAbilities(
        nbt: NBTItem,
        live: LiveMMOItem?,
        player: MMOPlayerData?,
        cooldowns: MutableMap<String, Double>,
        manaCosts: MutableMap<String, Double>,
    ) {
        val raw = runCatching {
            if (nbt.hasTag(ABILITY_TAG)) nbt.getString(ABILITY_TAG) else null
        }.getOrNull()

        if (!raw.isNullOrBlank()) {
            runCatching {
                JsonParser.parseString(raw).asJsonArray.forEach { element ->
                    val ability = element.asJsonObject
                    val skill = ability.get("Id")?.asString?.uppercase(Locale.ROOT) ?: return@forEach
                    var cooldown = 0.0
                    var manaCost = 0.0
                    ability.get("Modifiers")?.takeIf { it.isJsonObject }?.asJsonObject?.let { modifiers ->
                        for (key in arrayOf("cooldown", "skillcooldown", "barriercooldown")) {
                            if (modifiers.has(key)) {
                                cooldown = maxOf(cooldown, modifiers.get(key).asDouble)
                            }
                        }
                        manaCost = modifiers.get("mana")?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0
                    }
                    cooldowns[skill] = cooldown
                    // A skill bound to several triggers stays available if one can be paid for.
                    manaCosts.merge(skill, manaCost, ::minOf)
                }
            }
        }

        // Fallback (or fill missing values) with the loaded item data.
        if (live != null) {
            runCatching {
                if (live.hasData(ItemStats.ABILITIES)) {
                    val data = live.getData(ItemStats.ABILITIES) as? AbilityListData ?: return@runCatching
                    val liveManaCosts = HashMap<String, Double>()
                    data.abilities.forEach { ability ->
                        val skill = ability.handler.id.uppercase(Locale.ROOT)
                        val manaCost = if (ability.hasModifier("mana")) {
                            val base = ability.getParameter("mana")
                            runCatching { player?.skillModifierMap?.calculateValue(ability, "mana") }.getOrNull() ?: base
                        } else 0.0
                        liveManaCosts.merge(skill, manaCost, ::minOf)
                        val cooldown = maxOf(
                            ability.getParameter("cooldown"),
                            ability.getParameter("skillcooldown"),
                            ability.getParameter("barriercooldown"),
                        )
                        if ((cooldowns[skill] ?: 0.0) <= 0) cooldowns[skill] = cooldown
                    }
                    manaCosts.putAll(liveManaCosts)
                }
            }
        }
    }

    private fun hasClassRestriction(item: LiveMMOItem): Boolean {
        if (!item.hasData(ItemStats.REQUIRED_CLASS)) return false
        return (item.getData(ItemStats.REQUIRED_CLASS) as? StringListData)?.isEmpty() == false
    }

    private fun matchesRequiredClass(player: Player, item: LiveMMOItem): Boolean {
        val required = (item.getData(ItemStats.REQUIRED_CLASS) as? StringListData) ?: return true
        if (required.isEmpty()) return true
        val playerClasses = playerClasses(player)
        if (playerClasses.isEmpty()) return false
        return required.list.any { playerClasses.contains(normalize(it)) }
    }

    private fun playerClasses(player: Player): Set<String> {
        val keys = HashSet<String>()
        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
            val core = net.Indyuce.mmocore.api.player.PlayerData.get(player)
            core.profess?.let {
                keys += normalize(it.id)
                keys += normalize(it.name)
            }
        }
        val classId = PlayerData.get(player).rpg.className
        if (!classId.isNullOrBlank()) keys += normalize(classId)
        return keys
    }

    private fun requiredLevel(item: LiveMMOItem): Int {
        if (!item.hasData(ItemStats.REQUIRED_LEVEL)) return 0
        val data = item.getData(ItemStats.REQUIRED_LEVEL) as? RequiredLevelData ?: return 0
        return Math.ceil(data.value).toInt().coerceAtLeast(0)
    }

    private fun playerLevel(player: Player): Int {
        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
            return net.Indyuce.mmocore.api.player.PlayerData.get(player).level.coerceAtLeast(0)
        }
        return PlayerData.get(player).rpg.level.coerceAtLeast(0)
    }

    private fun normalize(value: String): String {
        return value.trim().uppercase(Locale.ROOT).replace(' ', '_')
    }
}
