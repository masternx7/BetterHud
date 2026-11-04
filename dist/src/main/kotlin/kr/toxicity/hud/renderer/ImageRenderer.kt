package kr.toxicity.hud.renderer

import kr.toxicity.hud.api.component.PixelComponent
import kr.toxicity.hud.api.component.WidthComponent
import kr.toxicity.hud.api.player.HudPlayer
import kr.toxicity.hud.api.update.UpdateEvent
import kr.toxicity.hud.image.ImageComponent
import kr.toxicity.hud.layout.ImageLayout
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.manager.PlayerManagerImpl
import kr.toxicity.hud.skill.SkillHudManager
import kr.toxicity.hud.util.*
import kotlin.math.ceil
import kotlin.math.roundToInt

class ImageRenderer(
    layout: ImageLayout,
    private val rawComponent: ImageComponent,
    private val cooldownRenderer: CooldownRenderer? = null,
) : ImageLayout by layout, HudRenderer {
    private val followHudPlayer = follow?.let {
        PlaceholderManagerImpl.find(it, this).assertString("This placeholder is not a string: $it")
    }

    override fun render(event: UpdateEvent): TickProvider<HudPlayer, PixelComponent> {
        val cond = conditions build event
        val listen = rawComponent.listener(event)
        val follow = followHudPlayer?.build(event)

        val stackGetter = stack?.build(event)
        val maxStackGetter = maxStack?.build(event)

        val mapper = rawComponent mapper event
        val colorApply = colorOverrides(event)

        return tickProvide(tick) build@ { player, frame ->
            val selectedRaw = mapper(player)

            val stackFrame = (stackGetter?.value(player) as? Number)?.toDouble() ?: 0.0
            val maxStackFrame = (maxStackGetter?.value(player) as? Number)?.toInt()?.coerceAtLeast(1) ?: ceil(stackFrame).toInt()

            var target = player
            follow?.let {
                PlayerManagerImpl.getHudPlayer(it.value(player).toString())?.let { p ->
                    target = p
                } ?: run {
                    if (cancelIfFollowerNotExists) return@build EMPTY_PIXEL_COMPONENT
                }
            }

            val skillId = skill
            if (skillId != null && !SkillHudManager.canRenderSkill(target, skillId)) {
                return@build EMPTY_PIXEL_COMPONENT
            }

            val remaining = skillId?.let { SkillHudManager.getRemainingCooldown(target, it) } ?: 0.0
            val selected = if (skillId != null) {
                val skillColor = if (remaining > 0) {
                    SkillHudManager.cooldownColorOrNull ?: color
                } else {
                    SkillHudManager.readyColorOrNull ?: color
                }
                selectedRaw.apply(outline, skillColor)
            } else {
                selectedRaw.apply(outline, color)
            }

            if (cond(target)) {
                val base = if (maxStackFrame > 1) {
                    if (stackFrame <= 0.0) return@build EMPTY_PIXEL_COMPONENT
                    var empty = EMPTY_PIXEL_COMPONENT
                    val range = 0..<maxStackFrame
                    for (i in if (reversed) range.reversed() else range) {
                        val frame = ((stackFrame - i - 0.1) * selected.images.size)
                            .roundToInt()
                            .coerceAtLeast(0)
                            .coerceAtMost(selected.images.lastIndex)
                        empty = empty.append(space, selected.images[frame])
                    }
                    empty.applyColor(colorApply(target))
                } else {
                    rawComponent.type.getComponent(listen, frame, selected, target)
                        .applyColor(colorApply(target))
                }
                if (skillId != null) applyCooldown(base, remaining) else base
            } else {
                if (clearListener) listen.clear(player)
                EMPTY_PIXEL_COMPONENT
            }
        }
    }

    private fun applyCooldown(base: PixelComponent, remaining: Double): PixelComponent {
        val renderer = cooldownRenderer ?: return base
        if (!SkillHudManager.isDigitEnabled || remaining <= 0) return base

        val text = SkillHudManager.formatCooldown(remaining)
        if (text.isEmpty()) return base

        val width = base.component.width
        var overlay = EMPTY_WIDTH_COMPONENT
        val shift = renderer.x - width
        if (shift != 0) overlay += shift.toSpaceComponent().finalizeFont()

        var cursor = renderer.x
        for (digit in text) {
            val glyph = renderer.digits[digit] ?: continue
            overlay += glyph
            cursor += renderer.advance
        }
        val endShift = width - cursor
        if (endShift != 0) overlay += endShift.toSpaceComponent().finalizeFont()

        val combined = base.component.copy()
        combined.component.append(overlay.component)
        return PixelComponent(combined, base.pixel)
    }

    fun max() = rawComponent.max
}
