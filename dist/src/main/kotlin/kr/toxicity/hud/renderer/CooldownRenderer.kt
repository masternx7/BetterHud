package kr.toxicity.hud.renderer

import kr.toxicity.hud.api.component.WidthComponent

/**
 * Pre-built digit glyphs for an image layout's `cooldown` overlay.
 */
data class CooldownRenderer(
    val x: Int,
    val advance: Int,
    val digits: Map<Char, WidthComponent>,
)
