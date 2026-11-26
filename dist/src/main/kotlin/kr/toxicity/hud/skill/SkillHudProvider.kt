package kr.toxicity.hud.skill

import kr.toxicity.hud.api.player.HudPlayer

/**
 * Bridge between BetterHud and an MMO item/cooldown provider (MMOItems, MythicLib, ...).
 *
 * The Bukkit bootstrap registers an implementation only when the required plugins are present.
 */
interface SkillHudProvider {

    /**
     * Reads the item currently held by the player.
     * Returns null when the player is not holding an MMO item.
     */
    fun resolve(player: HudPlayer): SkillHudItem?

    /**
     * Returns the remaining cooldown in seconds for a skill on the held item.
     */
    fun remainingCooldown(player: HudPlayer, skillId: String): Double
}

/**
 * Snapshot of the held MMO item.
 */
data class SkillHudItem(
    val type: String,
    val id: String,
    val skills: Set<String>,
    val maxCooldowns: Map<String, Double> = emptyMap(),
    val classMismatch: Boolean = false,
    val requiredLevel: Int = 0,
    val playerLevel: Int = 0,
    val levelMismatch: Boolean = false,
    val manaCosts: Map<String, Double> = emptyMap(),
)

/**
 * Global provider registry. The Bukkit bootstrap registers its implementation on startup.
 */
object SkillHudProviders {

    @Volatile
    private var current: SkillHudProvider? = null

    val provider: SkillHudProvider?
        get() = current

    fun register(provider: SkillHudProvider) {
        current = provider
    }

    fun unregister(provider: SkillHudProvider) {
        if (current === provider) current = null
    }
}
