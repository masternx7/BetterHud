package kr.toxicity.hud.layout

import kr.toxicity.hud.api.yaml.YamlObject
import kr.toxicity.hud.element.ImageElement
import kr.toxicity.hud.location.PixelLocation
import kr.toxicity.hud.manager.ImageManager
import kr.toxicity.hud.manager.PlaceholderManagerImpl
import kr.toxicity.hud.placeholder.PlaceholderBuilder
import kr.toxicity.hud.shader.HudShader
import kr.toxicity.hud.shader.ShaderGroup
import kr.toxicity.hud.skill.SkillHudManager
import kr.toxicity.hud.util.ifNull
import kr.toxicity.hud.util.toTextColor
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import java.util.Locale

interface ImageLayout : HudLayout<ImageElement> {
    val color: TextColor
    val scale: Double
    val space: Int
    val stack: PlaceholderBuilder<*>?
    val maxStack: PlaceholderBuilder<*>?
    val reversed: Boolean
    val clearListener: Boolean
    val skill: String?
    val maxCooldown: Double
    val cooldown: CooldownInfo?

    data class CooldownInfo(
        val x: Int,
        val y: Int,
        val scale: Double,
        val color: TextColor,
    )

    fun identifier(shader: HudShader, ascent: Int, fileName: String): HudLayout.Identifier {
        return ImageIdentifier(
            ShaderGroup(shader, fileName, ascent),
            this
        )
    }

    class ImageIdentifier(
        val delegate: HudLayout.Identifier,
        layout: ImageLayout
    ) : HudLayout.Identifier by delegate {
        val scale = layout.scale
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageIdentifier) return false

            if (scale != other.scale) return false
            if (delegate != other.delegate) return false

            return true
        }

        override fun hashCode(): Int {
            var result = scale.hashCode()
            result = 31 * result + delegate.hashCode()
            return result
        }
    }

    class Impl(
        override val source: ImageElement,
        group: LayoutGroup,
        yamlObject: YamlObject,
        loc: PixelLocation,
    ) : ImageLayout, HudLayout<ImageElement> by HudLayout.Impl(source, group, loc, yamlObject) {
        constructor(
            s: String,
            group: LayoutGroup,
            yamlObject: YamlObject,
            loc: PixelLocation,
        ): this(
            yamlObject["name"]?.asString().ifNull { "name value not set: $s" }.let { n ->
                ImageManager.getImage(n).ifNull { "this image doesn't exist: $n" }
            },
            group,
            yamlObject,
            loc
        )
        override val color: TextColor = yamlObject["color"]?.asString()?.toTextColor() ?: NamedTextColor.WHITE
        override val scale: Double = yamlObject.getAsDouble("scale", 1.0)
        override val space: Int = yamlObject.getAsInt("space", 1)
        override val stack: PlaceholderBuilder<*>? = yamlObject["stack"]?.asString()?.let {
            PlaceholderManagerImpl.find(it, this).assertNumber("this placeholder is not a number: $it")
        }
        override val maxStack: PlaceholderBuilder<*>? = yamlObject["max-stack"]?.asString()?.let {
            PlaceholderManagerImpl.find(it, this).assertNumber("this placeholder is not a number: $it")
        }
        override val reversed: Boolean = yamlObject.getAsBoolean("reversed", false)
        override val clearListener: Boolean = yamlObject.getAsBoolean("clear-listener", false)
        override val skill: String? = yamlObject["skill"]?.asString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase(Locale.ROOT)
        override val maxCooldown: Double = yamlObject.getAsDouble("max-cooldown", 0.0)
            .coerceAtLeast(0.0)
        override val cooldown: CooldownInfo? = yamlObject["cooldown"]?.asObject()?.let { section ->
            CooldownInfo(
                x = section.getAsInt("x", 4),
                y = section.getAsInt("y", 4),
                scale = section.getAsDouble("scale", 0.5).coerceAtLeast(0.01),
                color = section["color"]?.asString()?.toTextColor() ?: SkillHudManager.digitColor,
            )
        }

        init {
            skill?.let {
                if (maxCooldown > 0) SkillHudManager.registerMaxCooldown(it, maxCooldown)
            }
        }
    }
}