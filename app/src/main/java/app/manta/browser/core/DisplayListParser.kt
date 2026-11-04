package app.manta.browser.core

/**
 * Decodes Manta Core v2.0's `MANTA_FRAME` wire format (including `RECT`, `TEXT`, and `INPUT` boxes)
 * into hardware-accelerated Android Canvas draw primitives.
 */
sealed class DrawCmd {
    data class RectCmd(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val colorArgb: Int,
        val cornerRadius: Float
    ) : DrawCmd()

    data class TextCmd(
        val x: Float,
        val y: Float,
        val fontSizeSp: Float,
        val bold: Boolean,
        val colorArgb: Int,
        val linkHref: String?,
        val text: String
    ) : DrawCmd()

    data class InputCmd(
        val nodeId: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val isFocused: Boolean,
        val isChecked: Boolean,
        val displayText: String
    ) : DrawCmd()
}

data class ParsedMantaFrame(
    val title: String,
    val backgroundArgb: Int,
    val contentHeight: Float,
    val domNodesCount: Int,
    val ramKb: Float,
    val commands: List<DrawCmd>
)

object DisplayListParser {
    fun parse(wire: String): ParsedMantaFrame {
        if (wire.startsWith("MANTA_ERR|")) {
            val msg = wire.removePrefix("MANTA_ERR|")
            return ParsedMantaFrame(
                title = "Engine Error",
                backgroundArgb = 0xFF090A0F.toInt(),
                contentHeight = 400f,
                domNodesCount = 1,
                ramKb = 0f,
                commands = listOf(
                    DrawCmd.TextCmd(
                        x = 16f,
                        y = 32f,
                        fontSizeSp = 15f,
                        bold = true,
                        colorArgb = 0xFFFF897D.toInt(),
                        linkHref = null,
                        text = msg
                    )
                )
            )
        }

        val lines = wire.split('\n')
        var title = "Manta"
        var bg = 0xFF090A0F.toInt()
        var height = 600f
        var nodes = 0
        var ramKb = 0f
        val cmds = ArrayList<DrawCmd>(lines.size)

        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            when (parts[0]) {
                "MANTA_FRAME" -> {
                    for (i in 1 until parts.size) {
                        val kv = parts[i].split('=', limit = 2)
                        if (kv.size == 2) {
                            when (kv[0]) {
                                "title" -> title = kv[1]
                                "bg" -> bg = parseHexColor(kv[1], 0xFF090A0F.toInt())
                                "height" -> height = kv[1].toFloatOrNull() ?: 600f
                                "nodes" -> nodes = kv[1].toIntOrNull() ?: 0
                                "ramKB" -> ramKb = kv[1].toFloatOrNull() ?: 0f
                            }
                        }
                    }
                }
                "RECT" -> {
                    if (parts.size >= 7) {
                        cmds.add(
                            DrawCmd.RectCmd(
                                x = parts[1].toFloatOrNull() ?: 0f,
                                y = parts[2].toFloatOrNull() ?: 0f,
                                width = parts[3].toFloatOrNull() ?: 100f,
                                height = parts[4].toFloatOrNull() ?: 24f,
                                colorArgb = parseHexColor(parts[5], 0xFF191C26.toInt()),
                                cornerRadius = parts[6].toFloatOrNull() ?: 8f
                            )
                        )
                    }
                }
                "TEXT" -> {
                    if (parts.size >= 8) {
                        val href = parts[6].ifBlank { null }
                        cmds.add(
                            DrawCmd.TextCmd(
                                x = parts[1].toFloatOrNull() ?: 16f,
                                y = parts[2].toFloatOrNull() ?: 16f,
                                fontSizeSp = parts[3].toFloatOrNull() ?: 15f,
                                bold = parts[4] == "1",
                                colorArgb = parseHexColor(parts[5], 0xFFF1F5F9.toInt()),
                                linkHref = href,
                                text = parts[7]
                            )
                        )
                    }
                }
                "INPUT" -> {
                    if (parts.size >= 9) {
                        cmds.add(
                            DrawCmd.InputCmd(
                                nodeId = parts[1].toIntOrNull() ?: 0,
                                x = parts[2].toFloatOrNull() ?: 16f,
                                y = parts[3].toFloatOrNull() ?: 16f,
                                width = parts[4].toFloatOrNull() ?: 280f,
                                height = parts[5].toFloatOrNull() ?: 42f,
                                isFocused = parts[6] == "1",
                                isChecked = parts[7] == "1",
                                displayText = parts[8]
                            )
                        )
                    }
                }
            }
        }

        return ParsedMantaFrame(
            title = title,
            backgroundArgb = bg,
            contentHeight = height,
            domNodesCount = nodes,
            ramKb = ramKb,
            commands = cmds
        )
    }

    private fun parseHexColor(raw: String, fallback: Int): Int {
        val clean = raw.trim().removePrefix("#")
        return try {
            clean.toULong(16).toInt()
        } catch (_: Exception) {
            fallback
        }
    }
}
