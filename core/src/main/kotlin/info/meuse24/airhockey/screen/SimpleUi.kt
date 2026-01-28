package info.meuse24.airhockey.screen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Disposable

object SimpleUi : Disposable {
    private var _font: BitmapFont? = null
    private var _pixel: Texture? = null
    private var _skin: Skin? = null

    val skin: Skin
        get() {
            if (_skin == null) {
                initialize()
            }
            return _skin!!
        }

    private fun initialize() {
        _font = BitmapFont().apply {
            data.setScale(3f)
        }
        _pixel = Texture(Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
            setColor(Color.WHITE)
            fill()
        })

        // Create bordered panel texture
        val panelPixmap = Pixmap(32, 32, Pixmap.Format.RGBA8888).apply {
            // Border
            setColor(Color(0.3f, 0.6f, 0.9f, 1f))
            drawRectangle(0, 0, 32, 32)
            drawRectangle(1, 1, 30, 30)
            // Background
            setColor(Color(0.05f, 0.05f, 0.1f, 0.95f))
            fillRectangle(2, 2, 28, 28)
        }
        val panelTexture = Texture(panelPixmap)
        panelPixmap.dispose()

        _skin = Skin().apply {
            add("default-font", _font)
            add("pixel", _pixel)
            add("panel", panelTexture)

            // Default Label
            val labelStyle = Label.LabelStyle().apply {
                font = _font
                fontColor = Color.WHITE
            }
            add("default", labelStyle)

            // Title Label (larger, cyan)
            val titleStyle = Label.LabelStyle().apply {
                font = _font
                fontColor = Color(0.3f, 0.8f, 1f, 1f)
            }
            add("title", titleStyle)

            // Small Label (stats)
            val smallStyle = Label.LabelStyle().apply {
                font = _font
                fontColor = Color(0.7f, 0.7f, 0.7f, 1f)
            }
            add("small", smallStyle)

            // Primary Button (cyan)
            val buttonStyle = TextButton.TextButtonStyle().apply {
                font = _font
                fontColor = Color.WHITE
                up = TextureRegionDrawable(_pixel).tint(Color(0.2f, 0.6f, 0.9f, 1f))
                down = TextureRegionDrawable(_pixel).tint(Color(0.1f, 0.4f, 0.7f, 1f))
                over = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.7f, 1f, 1f))
                disabled = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.3f, 0.3f, 1f))
            }
            add("default", buttonStyle)

            // Danger Button (red)
            val dangerButtonStyle = TextButton.TextButtonStyle().apply {
                font = _font
                fontColor = Color.WHITE
                up = TextureRegionDrawable(_pixel).tint(Color(0.8f, 0.2f, 0.2f, 1f))
                down = TextureRegionDrawable(_pixel).tint(Color(0.6f, 0.1f, 0.1f, 1f))
                over = TextureRegionDrawable(_pixel).tint(Color(0.9f, 0.3f, 0.3f, 1f))
                disabled = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.3f, 0.3f, 1f))
            }
            add("danger", dangerButtonStyle)

            // Success Button (green)
            val successButtonStyle = TextButton.TextButtonStyle().apply {
                font = _font
                fontColor = Color.WHITE
                up = TextureRegionDrawable(_pixel).tint(Color(0.2f, 0.8f, 0.3f, 1f))
                down = TextureRegionDrawable(_pixel).tint(Color(0.1f, 0.6f, 0.2f, 1f))
                over = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.9f, 0.4f, 1f))
                disabled = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.3f, 0.3f, 1f))
            }
            add("success", successButtonStyle)

            // Peer Button (light)
            val peerButtonStyle = TextButton.TextButtonStyle().apply {
                font = _font
                fontColor = Color.WHITE
                up = TextureRegionDrawable(_pixel).tint(Color(0.15f, 0.15f, 0.2f, 1f))
                down = TextureRegionDrawable(_pixel).tint(Color(0.1f, 0.1f, 0.15f, 1f))
                over = TextureRegionDrawable(_pixel).tint(Color(0.2f, 0.5f, 0.8f, 1f))
            }
            add("peer", peerButtonStyle)

            // ScrollPane with panel background
            val scrollPaneStyle = ScrollPane.ScrollPaneStyle().apply {
                background = NinePatchDrawable(NinePatch(panelTexture, 2, 2, 2, 2))
                vScroll = TextureRegionDrawable(_pixel).tint(Color(0.2f, 0.2f, 0.3f, 1f))
                vScrollKnob = TextureRegionDrawable(_pixel).tint(Color(0.4f, 0.6f, 0.9f, 1f))
            }
            add("default", scrollPaneStyle)
        }
    }

    override fun dispose() {
        _font?.dispose()
        _pixel?.dispose()
        _skin?.getDrawable("panel")?.let {
            if (it is NinePatchDrawable) it.patch.texture.dispose()
        }
        _skin?.dispose()
        _font = null
        _pixel = null
        _skin = null
    }
}
