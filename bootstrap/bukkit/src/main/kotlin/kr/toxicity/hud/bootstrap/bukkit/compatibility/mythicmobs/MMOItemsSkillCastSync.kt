package kr.toxicity.hud.bootstrap.bukkit.compatibility.mythicmobs

import io.lumine.mythic.api.adapters.AbstractEntity
import io.lumine.mythic.api.skills.Skill
import io.lumine.mythic.api.skills.SkillCaster
import io.lumine.mythic.api.skills.SkillMetadata
import io.lumine.mythic.bukkit.events.MythicSkillEvent
import io.lumine.mythic.lib.api.item.NBTItem
import io.lumine.mythic.lib.api.player.MMOPlayerData
import kr.toxicity.hud.manager.PlayerManagerImpl
import kr.toxicity.hud.skill.SkillHudManager
import kr.toxicity.hud.util.taskLater
import net.Indyuce.mmoitems.ItemStats
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem
import net.Indyuce.mmoitems.stat.data.AbilityData
import net.Indyuce.mmoitems.stat.data.AbilityListData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.Set
import java.util.regex.Pattern

/**
 * When MythicMobs casts a skill that is attached to an MMOItem, mirror the cast
 * cooldown into MythicLib's cooldown map so the HUD can display it.
 */
class MMOItemsSkillCastSync : Listener {

    companion object {
        private const val MIN_REAL_COOLDOWN_SECONDS = 0.5

        private val CD_SUBSKILL = Pattern.compile(
            "^(?<base>.+?)(_SKILL|_Skill|_CAST|_Cast)$",
            Pattern.CASE_INSENSITIVE
        )

        private val IGNORE_SUFFIXES = Set.of(
            "STACK", "AURA", "DAMAGE", "DAMAGE_D", "DAMAGE_DD", "PRO", "ST", "MARK", "TICK"
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMythicSkill(event: MythicSkillEvent) {
        val skill = event.skill ?: return
        val metadata = event.skillMetadata ?: return
        val mythicName = skill.internalName?.takeIf { it.isNotBlank() } ?: return
        val baseId = cooldownBearingBaseId(mythicName) ?: return
        val player = resolvePlayer(metadata) ?: return
        applyFromHeldItem(player, skill, metadata, baseId)
    }

    private fun applyFromHeldItem(player: Player, skill: Skill, metadata: SkillMetadata, baseId: String) {
        val ability = findAbilityOnHeldItem(player, baseId) ?: return
        if (modifier(ability, "cooldown") > 0) return

        var duration = skillCooldownDuration(player, ability)
        if (duration < MIN_REAL_COOLDOWN_SECONDS) return

        val path = cooldownPath(baseId)
        if (MythicMobsCooldownBridge.hasConfiguredCooldown(skill)) {
            val configured = MythicMobsCooldownBridge.getConfiguredCooldownSeconds(skill, metadata)
            duration = maxOf(duration, configured)
            if (duration >= MIN_REAL_COOLDOWN_SECONDS) {
                applyCooldown(player, path, duration)
            }
        }

        scheduleCandidatePolls(player, baseId, path)
    }

    private fun scheduleCandidatePolls(player: Player, baseId: String, path: String) {
        for (delay in longArrayOf(1L, 2L, 4L, 8L, 15L)) {
            taskLater(delay) {
                if (!player.isOnline) return@taskLater
                val mythic = MythicMobsCooldownBridge.getRemainingCooldown(player, baseId)
                if (mythic < MIN_REAL_COOLDOWN_SECONDS) return@taskLater
                applyCooldown(player, path, mythic)
            }
        }
    }

    private fun skillCooldownDuration(player: Player, ability: AbilityData): Double {
        var best = 0.0
        best = maxOf(best, modifier(ability, "skillcooldown"))
        best = maxOf(best, modifier(ability, "barriercooldown"))
        if (best > 0) return best
        val hudPlayer = PlayerManagerImpl.getHudPlayer(player.uniqueId) ?: return 0.0
        val skillId = ability.handler?.id ?: return 0.0
        return SkillHudManager.getMaxCooldown(hudPlayer, skillId)
    }

    private fun findAbilityOnHeldItem(player: Player, skillId: String): AbilityData? {
        val item: ItemStack = player.inventory.itemInMainHand
        if (item.type.isAir) return null
        return runCatching {
            val nbt = NBTItem.get(item)
            if (!nbt.hasType()) return null
            val live = LiveMMOItem(nbt)
            if (!live.hasData(ItemStats.ABILITIES)) return null
            (live.getData(ItemStats.ABILITIES) as AbilityListData).abilities.firstOrNull {
                it.handler?.id?.equals(skillId, ignoreCase = true) == true
            }
        }.getOrNull()
    }

    private fun modifier(ability: AbilityData, key: String): Double {
        if (!ability.hasModifier(key)) return 0.0
        return ability.getParameter(key)
    }

    private fun applyCooldown(player: Player, path: String, seconds: Double) {
        if (seconds <= 0) return
        val data = MMOPlayerData.get(player)
        if (data.cooldownMap.getCooldown(path) >= seconds - 0.05) return
        data.cooldownMap.applyCooldown(path, seconds)
    }

    private fun resolvePlayer(metadata: SkillMetadata): Player? {
        val caster: SkillCaster = metadata.caster ?: return null
        val entity: AbstractEntity = caster.entity ?: return null
        val bukkit: Entity = entity.bukkitEntity
        return bukkit as? Player
    }

    private fun cooldownBearingBaseId(mythicName: String): String? {
        val matcher = CD_SUBSKILL.matcher(mythicName)
        if (!matcher.matches()) return null
        val base = matcher.group("base")?.takeIf { it.isNotBlank() } ?: return null
        val upper = base.uppercase(Locale.ROOT)
        for (ignore in IGNORE_SUFFIXES) {
            if (upper.endsWith("_$ignore") || upper == ignore) return null
        }
        return upper
    }

    private fun cooldownPath(skillId: String): String {
        return "skill_" + skillId.lowercase(Locale.ROOT)
    }
}
