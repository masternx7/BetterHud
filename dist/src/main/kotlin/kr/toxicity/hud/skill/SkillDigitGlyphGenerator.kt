package kr.toxicity.hud.skill

import kr.toxicity.hud.util.DATA_FOLDER
import kr.toxicity.hud.util.warn
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Generates the 0-9 bitmaps used by the cooldown overlay.
 *
 * This is intentionally small: it only creates the ten glyph images that are added
 * to BetterHud's own font/pack pipeline. No shader or resource pack from another
 * plugin is used.
 */
object SkillDigitGlyphGenerator {

    private data class Key(val baseSize: Int, val fontName: String)

    private val cache = ConcurrentHashMap<Key, DigitAtlas>()

    fun generate(baseSize: Int, fontName: String): DigitAtlas {
        return cache.computeIfAbsent(Key(baseSize, fontName)) {
            create(baseSize, fontName)
        }
    }

    private fun create(baseSize: Int, fontName: String): DigitAtlas {
        val size = max(4, baseSize)
        val font = loadFont(fontName, size)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        probeGraphics.font = font
        val metrics = probeGraphics.fontMetrics
        var cellWidth = 1
        for (digit in '0'..'9') {
            cellWidth = max(cellWidth, metrics.charWidth(digit))
        }
        cellWidth += 2
        val cellHeight = max(1, metrics.height + 2)
        probeGraphics.dispose()

        val pngs = LinkedHashMap<Char, ByteArray>()
        for (digit in '0'..'9') {
            val image = BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                )
                graphics.font = font
                graphics.color = Color.WHITE
                val digitMetrics = graphics.fontMetrics
                val x = (cellWidth - digitMetrics.charWidth(digit)) / 2
                val y = (cellHeight - digitMetrics.height) / 2 + digitMetrics.ascent
                graphics.drawString(digit.toString(), x, y)
            } finally {
                graphics.dispose()
            }
            pngs[digit] = toPng(image)
        }

        val id = "skill_digits_${size}_${Integer.toHexString(fontName.hashCode())}"
        return DigitAtlas(pngs, cellWidth, cellHeight, id)
    }

    private fun loadFont(fontName: String, size: Int): Font {
        if (fontName.isNotBlank()) {
            runCatching {
                var file = File(fontName)
                if (!file.isAbsolute) {
                    file = File(DATA_FOLDER, "fonts/${fontName}")
                }
                if (file.isFile) {
                    return Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(size.toFloat())
                }
            }.onFailure {
                warn("Unable to load cooldown digit font '$fontName'. Using SansSerif.")
            }
        }
        return Font("SansSerif", Font.BOLD, size)
    }

    private fun toPng(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }

    data class DigitAtlas(
        val pngs: Map<Char, ByteArray>,
        val width: Int,
        val height: Int,
        val id: String,
    ) {
        fun textureName(digit: Char): String = "${id}_${digit}.png"
    }
}
