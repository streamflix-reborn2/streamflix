package com.streamflixreborn.streamflix.fragments.player.settings

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemSettingMobileBinding
import com.streamflixreborn.streamflix.databinding.ViewPlayerSettingsMobileBinding
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.margin

class PlayerSettingsMobileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : PlayerSettingsView(context, attrs, defStyleAttr) {

    val binding = ViewPlayerSettingsMobileBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private val settingsAdapter = SettingsAdapter(this, Settings.listMobile)
    private val qualityAdapter = SettingsAdapter(this, Settings.Quality.list)
    private val audioAdapter = SettingsAdapter(this, Settings.Audio.list)
    private val subtitlesAdapter = SettingsAdapter(this, Settings.Subtitle.list)
    private val subtitleOffsetAdapter = SettingsAdapter(this, Settings.Subtitle.Offset.list)
    private val captionStyleAdapter = SettingsAdapter(this, Settings.Subtitle.Style.list)
    private val fontColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.FontColor.list)
    private val textSizeAdapter = SettingsAdapter(this, Settings.Subtitle.Style.TextSize.list)
    private val fontOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.FontOpacity.list)
    private val edgeStyleAdapter = SettingsAdapter(this, Settings.Subtitle.Style.EdgeStyle.list)
    private val backgroundColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.BackgroundColor.list)
    private val backgroundOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.BackgroundOpacity.list)
    private val windowColorAdapter = SettingsAdapter(this, Settings.Subtitle.Style.WindowColor.list)
    private val windowOpacityAdapter = SettingsAdapter(this, Settings.Subtitle.Style.WindowOpacity.list)
    private val openSubtitlesAdapter = SettingsAdapter(this, Settings.Subtitle.OpenSubtitles.list)
    private val subDLAdapter = SettingsAdapter(this, Settings.Subtitle.SubDLSubtitles.list)
    private val speedAdapter = SettingsAdapter(this, Settings.Speed.list)
    private val extraBufferingAdapter = SettingsAdapter(this, Settings.ExtraBuffering.list)
    private val softwareDecoderAdapter = SettingsAdapter(this, Settings.SoftwareDecoder.list)
    private val gesturesAdapter = SettingsAdapter(this, Settings.Gestures.list)
    private val keepScreenOnAdapter = SettingsAdapter(this, Settings.KeepScreenOn.list)
    private val serversAdapter = SettingsAdapter(this, Settings.Server.list)
    private val marginAdapter = SettingsAdapter(this, Settings.Subtitle.Style.Margin.list)

    override var onSubtitlesClicked: (() -> Unit)? = null
    var onManualZoomClicked: (() -> Unit)? = null

    init {
        binding.btnSettingsClose.setOnClickListener {
            hide()
        }
        binding.btnSettingsBack.setOnClickListener {
            onBackPressed()
        }
    }

    fun onBackPressed(): Boolean {
        when (currentSettings) {
            Setting.MAIN -> hide()
            Setting.QUALITY,
            Setting.AUDIO,
            Setting.SUBTITLES,
            Setting.SPEED,
            Setting.SERVERS,
            Setting.GESTURES,
            Setting.KEEP_SCREEN_ON,
            Setting.EXTRA_BUFFERING,
            Setting.SOFTWARE_DECODER,
            Setting.MANUAL_ZOOM -> displaySettings(Setting.MAIN)
            Setting.SUBTITLE_OFFSET -> displaySettings(Setting.SUBTITLES)
            Setting.CAPTION_STYLE -> displaySettings(Setting.SUBTITLES)
            Setting.CAPTION_STYLE_FONT_COLOR,
            Setting.CAPTION_STYLE_TEXT_SIZE,
            Setting.CAPTION_STYLE_FONT_OPACITY,
            Setting.CAPTION_STYLE_EDGE_STYLE,
            Setting.CAPTION_STYLE_BACKGROUND_COLOR,
            Setting.CAPTION_STYLE_BACKGROUND_OPACITY,
            Setting.CAPTION_STYLE_WINDOW_COLOR,
            Setting.CAPTION_STYLE_WINDOW_OPACITY,
            Setting.CAPTION_STYLE_MARGIN -> displaySettings(Setting.CAPTION_STYLE)
            Setting.OPEN_SUBTITLES -> displaySettings(Setting.SUBTITLES)
            Setting.SUBDL -> displaySettings(Setting.SUBTITLES)
        }
        return true
    }

    fun show() {
        this.visibility = View.VISIBLE

        displaySettings(Setting.MAIN)
    }

    private fun displaySettings(setting: Setting) {
        currentSettings = setting

        if (setting == Setting.SUBTITLES) {
            onSubtitlesClicked?.invoke()
        }

        binding.tvSettingsHeader.apply {
            text = when (setting) {
                Setting.MAIN -> context.getString(R.string.player_settings_title)
                Setting.QUALITY -> context.getString(R.string.player_settings_quality_title)
                Setting.AUDIO -> context.getString(R.string.player_settings_audio_title)
                Setting.SUBTITLES -> context.getString(R.string.player_settings_subtitles_title)
                Setting.SUBTITLE_OFFSET -> context.getString(R.string.player_settings_subtitle_offset_title)
                Setting.CAPTION_STYLE -> context.getString(R.string.player_settings_caption_style_title)
                Setting.CAPTION_STYLE_FONT_COLOR -> context.getString(R.string.player_settings_caption_style_font_color_title)
                Setting.CAPTION_STYLE_TEXT_SIZE -> context.getString(R.string.player_settings_caption_style_text_size_title)
                Setting.CAPTION_STYLE_FONT_OPACITY -> context.getString(R.string.player_settings_caption_style_font_opacity_title)
                Setting.CAPTION_STYLE_EDGE_STYLE -> context.getString(R.string.player_settings_caption_style_edge_style_title)
                Setting.CAPTION_STYLE_BACKGROUND_COLOR -> context.getString(R.string.player_settings_caption_style_background_color_title)
                Setting.CAPTION_STYLE_BACKGROUND_OPACITY -> context.getString(R.string.player_settings_caption_style_background_opacity_title)
                Setting.CAPTION_STYLE_WINDOW_COLOR -> context.getString(R.string.player_settings_caption_style_window_color_title)
                Setting.CAPTION_STYLE_WINDOW_OPACITY -> context.getString(R.string.player_settings_caption_style_window_opacity_title)
                Setting.OPEN_SUBTITLES -> context.getString(R.string.player_settings_open_subtitles_title)
                Setting.SUBDL -> context.getString(R.string.player_settings_subdl_title)
                Setting.SPEED -> context.getString(R.string.player_settings_speed_title)
                Setting.EXTRA_BUFFERING -> context.getString(R.string.player_settings_extra_buffer_title)
                Setting.SOFTWARE_DECODER -> context.getString(R.string.player_settings_software_decoder_title)
                Setting.SERVERS -> context.getString(R.string.player_settings_servers_title)
                Setting.CAPTION_STYLE_MARGIN -> context.getString(R.string.player_settings_caption_style_margin_title)
                Setting.GESTURES -> context.getString(R.string.player_settings_gestures_title)
                Setting.KEEP_SCREEN_ON -> context.getString(R.string.player_settings_keep_screen_on_title)
                Setting.MANUAL_ZOOM -> context.getString(R.string.player_settings_manual_zoom_label)
            }
        }

        binding.btnSettingsBack.visibility = if (setting == Setting.MAIN) View.GONE else View.VISIBLE

        binding.rvSettings.adapter = when (setting) {
            Setting.MAIN -> settingsAdapter
            Setting.QUALITY -> qualityAdapter
            Setting.AUDIO -> audioAdapter
            Setting.SUBTITLES -> subtitlesAdapter
            Setting.SUBTITLE_OFFSET -> subtitleOffsetAdapter
            Setting.CAPTION_STYLE -> captionStyleAdapter
            Setting.CAPTION_STYLE_FONT_COLOR -> fontColorAdapter
            Setting.CAPTION_STYLE_TEXT_SIZE -> textSizeAdapter
            Setting.CAPTION_STYLE_FONT_OPACITY -> fontOpacityAdapter
            Setting.CAPTION_STYLE_EDGE_STYLE -> edgeStyleAdapter
            Setting.CAPTION_STYLE_BACKGROUND_COLOR -> backgroundColorAdapter
            Setting.CAPTION_STYLE_BACKGROUND_OPACITY -> backgroundOpacityAdapter
            Setting.CAPTION_STYLE_WINDOW_COLOR -> windowColorAdapter
            Setting.CAPTION_STYLE_WINDOW_OPACITY -> windowOpacityAdapter
            Setting.OPEN_SUBTITLES -> openSubtitlesAdapter
            Setting.SUBDL -> subDLAdapter
            Setting.SPEED -> speedAdapter
            Setting.EXTRA_BUFFERING -> extraBufferingAdapter
            Setting.SOFTWARE_DECODER -> softwareDecoderAdapter
            Setting.SERVERS -> serversAdapter
            Setting.CAPTION_STYLE_MARGIN -> marginAdapter
            Setting.GESTURES -> gesturesAdapter
            Setting.KEEP_SCREEN_ON -> keepScreenOnAdapter
            Setting.MANUAL_ZOOM -> settingsAdapter
        }

        if (setting == Setting.SUBTITLE_OFFSET) {
            scrollToSelectedSubtitleOffset()
        }
    }

    private fun scrollToSelectedSubtitleOffset() {
        val selectedOffset = Settings.Subtitle.Offset.selected.milliseconds
        val selectedPosition = Settings.Subtitle.Offset.list.indexOfFirst {
            it is Settings.Subtitle.Offset.Value && it.milliseconds == selectedOffset
        }

        if (selectedPosition >= 0) {
            binding.rvSettings.post {
                (binding.rvSettings.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(selectedPosition, binding.rvSettings.height / 2)
            }
        }
    }

    fun hide() {
        this.visibility = View.GONE
    }


    private class SettingsAdapter(
        private val settingsView: PlayerSettingsMobileView,
        private val items: List<Item>,
    ) : RecyclerView.Adapter<SettingViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SettingViewHolder(
                settingsView,
                ItemSettingMobileBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun onBindViewHolder(holder: SettingViewHolder, position: Int) {
            holder.displaySettings(items[position])
        }

        override fun getItemCount() = items.size
    }

    private class SettingViewHolder(
        private val settingsView: PlayerSettingsMobileView,
        private val binding: ItemSettingMobileBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun displaySettings(item: Item) {
            binding.root.apply {
                when (item) {
                    Settings.Subtitle.Style,
                    Settings.Subtitle.Style.ResetStyle -> margin(bottom = 6.dp(context))
                    Settings.Subtitle.LocalSubtitles -> margin(top = 6.dp(context))
                    else -> margin(bottom = 0, top = 0)
                }
                setOnClickListener {
                    when (item) {
                        is Settings -> {
                            when (item) {
                                Settings.Quality -> settingsView.displaySettings(Setting.QUALITY)
                                Settings.Audio -> settingsView.displaySettings(Setting.AUDIO)
                                Settings.Subtitle -> settingsView.displaySettings(Setting.SUBTITLES)
                                Settings.Speed -> settingsView.displaySettings(Setting.SPEED)
                                Settings.ExtraBuffering -> settingsView.displaySettings(Setting.EXTRA_BUFFERING)
                                Settings.SoftwareDecoder -> settingsView.displaySettings(Setting.SOFTWARE_DECODER)
                                Settings.Server -> settingsView.displaySettings(Setting.SERVERS)
                                Settings.Gestures -> settingsView.displaySettings(Setting.GESTURES)
                                Settings.KeepScreenOn -> settingsView.displaySettings(Setting.KEEP_SCREEN_ON)
                                Settings.ManualZoom -> {
                                    settingsView.onManualZoomClicked?.invoke()
                                    settingsView.hide()
                                }
                            }
                        }

                        is Settings.Quality -> {
                            settingsView.onQualitySelected.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Audio -> {
                            settingsView.onAudioSelected.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Subtitle -> {
                            when (item) {
                                Settings.Subtitle.Style -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE)
                                }

                                Settings.Subtitle.Offset -> {
                                    settingsView.displaySettings(Setting.SUBTITLE_OFFSET)
                                }

                                is Settings.Subtitle.None,
                                is Settings.Subtitle.TextTrackInformation -> {
                                    settingsView.onSubtitleSelected.invoke(item)
                                    settingsView.displaySettings(Setting.MAIN)
                                }

                                Settings.Subtitle.LocalSubtitles -> {
                                    settingsView.onLocalSubtitlesClicked?.invoke()
                                    settingsView.hide()
                                }

                                Settings.Subtitle.OpenSubtitles -> {
                                    settingsView.displaySettings(Setting.OPEN_SUBTITLES)
                                }

                                Settings.Subtitle.SubDLSubtitles -> {
                                    settingsView.displaySettings(Setting.SUBDL)
                                }
                            }
                        }

                        is Settings.Subtitle.Offset.Value -> {
                            settingsView.onSubtitleOffsetSelected.invoke(item)
                            settingsView.displaySettings(Setting.SUBTITLES)
                        }

                        is Settings.Subtitle.Style -> {
                            when (item) {
                                Settings.Subtitle.Style.ResetStyle -> {
                                    settingsView.onTextSizeSelected.invoke(Settings.Subtitle.Style.TextSize.DEFAULT)
                                    settingsView.onCaptionStyleChanged.invoke(Settings.Subtitle.Style.DEFAULT)
                                    settingsView.hide()
                                }
                                Settings.Subtitle.Style.FontColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_FONT_COLOR)
                                }
                                Settings.Subtitle.Style.TextSize -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_TEXT_SIZE)
                                }
                                Settings.Subtitle.Style.FontOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_FONT_OPACITY)
                                }
                                Settings.Subtitle.Style.EdgeStyle -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_EDGE_STYLE)
                                }
                                Settings.Subtitle.Style.BackgroundColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_BACKGROUND_COLOR)
                                }
                                Settings.Subtitle.Style.BackgroundOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_BACKGROUND_OPACITY)
                                }
                                Settings.Subtitle.Style.WindowColor -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_WINDOW_COLOR)
                                }
                                Settings.Subtitle.Style.WindowOpacity -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_WINDOW_OPACITY)
                                }
                                Settings.Subtitle.Style.Margin -> {
                                    settingsView.displaySettings(Setting.CAPTION_STYLE_MARGIN)
                                }
                            }
                        }

                        is Settings.Subtitle.Style.FontColor -> {
                            settingsView.onFontColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.TextSize -> {
                            settingsView.onTextSizeSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.FontOpacity -> {
                            settingsView.onFontOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.EdgeStyle -> {
                            settingsView.onEdgeStyleSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.BackgroundColor -> {
                            settingsView.onBackgroundColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.BackgroundOpacity -> {
                            settingsView.onBackgroundOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.WindowColor -> {
                            settingsView.onWindowColorSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.WindowOpacity -> {
                            settingsView.onWindowOpacitySelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.Style.Margin -> {
                            settingsView.onMarginSelected.invoke(item)
                            settingsView.displaySettings(Setting.CAPTION_STYLE)
                        }

                        is Settings.Subtitle.OpenSubtitles.Subtitle -> {
                            settingsView.onOpenSubtitleSelected?.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Subtitle.SubDLSubtitles.Subtitle -> {
                            settingsView.onSubDLSubtitleSelected?.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Speed -> {
                            settingsView.onSpeedSelected.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.ExtraBuffering -> {
                            settingsView.onExtraBufferingSelected.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.SoftwareDecoder -> {
                            settingsView.onSoftwareDecoderSelected.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Gestures -> {
                            when (item) {
                                is Settings.Gestures.On -> com.streamflixreborn.streamflix.utils.UserPreferences.playerGestures = true
                                is Settings.Gestures.Off -> com.streamflixreborn.streamflix.utils.UserPreferences.playerGestures = false
                            }
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.KeepScreenOn -> {
                            when (item) {
                                is Settings.KeepScreenOn.On -> com.streamflixreborn.streamflix.utils.UserPreferences.keepScreenOnWhenPaused = true
                                is Settings.KeepScreenOn.Off -> com.streamflixreborn.streamflix.utils.UserPreferences.keepScreenOnWhenPaused = false
                            }
                            settingsView.displaySettings(Setting.MAIN)
                        }

                        is Settings.Server -> {
                            settingsView.onServerSelected?.invoke(item)
                            settingsView.displaySettings(Setting.MAIN)
                        }
                        else -> {}
                    }
                }
            }

            // CLEAN RESET
            binding.vSettingColor.visibility = View.GONE
            binding.ivSettingIcon.visibility = View.GONE

            // Handle Color Dots
            val color = when (item) {
                is Settings.Subtitle.Style.FontColor -> item.color
                is Settings.Subtitle.Style.BackgroundColor -> item.color
                is Settings.Subtitle.Style.WindowColor -> item.color
                else -> null
            }
            if (color != null) {
                binding.vSettingColor.backgroundTintList = ColorStateList.valueOf(color)
                binding.vSettingColor.visibility = View.VISIBLE
            }

            // Handle Icons for Main categories only
            if (item is Settings) {
                binding.ivSettingIcon.visibility = View.VISIBLE
                binding.ivSettingIcon.setImageDrawable(
                    ContextCompat.getDrawable(binding.root.context, when (item) {
                        Settings.Quality -> R.drawable.ic_player_settings_quality
                        Settings.Audio -> R.drawable.ic_player_settings_audio
                        Settings.Subtitle -> if (Settings.Subtitle.selected is Settings.Subtitle.TextTrackInformation) R.drawable.ic_player_settings_subtitle_on else R.drawable.ic_player_settings_subtitle_off
                        Settings.Speed -> R.drawable.ic_player_settings_playback_speed
                        Settings.ExtraBuffering -> R.drawable.ic_player_settings_extra_buffer
                        Settings.SoftwareDecoder -> R.drawable.ic_player_settings_extra_buffer
                        Settings.Server -> R.drawable.ic_player_settings_servers
                        Settings.Gestures -> R.drawable.ic_player_settings_gestures
                        Settings.KeepScreenOn -> R.drawable.ic_brightness
                        Settings.ManualZoom -> R.drawable.exo_styled_controls_aspect_ratio
                    })
                )
            }

            binding.tvSettingMainText.apply {
                text = when (item) {
                    is Settings -> when (item) {
                        Settings.Quality -> context.getString(R.string.player_settings_quality_label)
                        Settings.Audio -> context.getString(R.string.player_settings_audio_label)
                        Settings.Subtitle -> context.getString(R.string.player_settings_subtitles_label)
                        Settings.Speed -> context.getString(R.string.player_settings_speed_label)
                        Settings.ExtraBuffering -> context.getString(R.string.player_settings_extra_buffer_server_label)
                        Settings.SoftwareDecoder -> context.getString(R.string.player_settings_software_decoder_label)
                        Settings.Server -> context.getString(R.string.player_settings_servers_label)
                        Settings.Gestures -> context.getString(R.string.player_settings_gestures_title)
                        Settings.KeepScreenOn -> context.getString(R.string.player_settings_keep_screen_on_title)
                        Settings.ManualZoom -> context.getString(R.string.player_settings_manual_zoom_label)
                    }

                    is Settings.Audio -> when (item) {
                        is Settings.Audio.AudioTrackInformation -> item.name
                    }

                    is Settings.Quality -> when (item) {
                        is Settings.Quality.Auto -> when {
                            item.isSelected -> when (val track = item.currentTrack) {
                                null -> context.getString(R.string.player_settings_quality_auto)
                                else -> context.getString(
                                    R.string.player_settings_quality_auto_selected,
                                    track.height
                                )
                            }

                            else -> context.getString(R.string.player_settings_quality_auto)
                        }
                        is Settings.Quality.VideoTrackInformation -> context.getString(
                            R.string.player_settings_quality,
                            item.height
                        )
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> context.getString(R.string.player_settings_caption_style_label)
                        Settings.Subtitle.Offset -> context.getString(R.string.player_settings_subtitle_offset_label)
                        is Settings.Subtitle.None -> context.getString(R.string.player_settings_subtitles_off)
                        is Settings.Subtitle.TextTrackInformation -> item.label.ifEmpty { item.name }
                        Settings.Subtitle.LocalSubtitles -> context.getString(R.string.player_settings_local_subtitles_label)
                        Settings.Subtitle.OpenSubtitles -> context.getString(R.string.player_settings_open_subtitles_label)
                        Settings.Subtitle.SubDLSubtitles -> context.getString(R.string.player_settings_subdl_label)
                    }

                    is Settings.Subtitle.Offset.Value -> context.getString(
                        R.string.player_settings_subtitle_offset_seconds,
                        item.milliseconds / 1_000.0,
                    )

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> context.getString(R.string.player_settings_caption_style_reset_style_label)
                        Settings.Subtitle.Style.FontColor -> context.getString(R.string.player_settings_caption_style_font_color_label)
                        Settings.Subtitle.Style.TextSize -> context.getString(R.string.player_settings_caption_style_text_size_label)
                        Settings.Subtitle.Style.FontOpacity -> context.getString(R.string.player_settings_caption_style_font_opacity_label)
                        Settings.Subtitle.Style.EdgeStyle -> context.getString(R.string.player_settings_caption_style_edge_style_label)
                        Settings.Subtitle.Style.BackgroundColor -> context.getString(R.string.player_settings_caption_style_background_color_label)
                        Settings.Subtitle.Style.BackgroundOpacity -> context.getString(R.string.player_settings_caption_style_background_opacity_label)
                        Settings.Subtitle.Style.WindowColor -> context.getString(R.string.player_settings_caption_style_window_color_label)
                        Settings.Subtitle.Style.WindowOpacity -> context.getString(R.string.player_settings_caption_style_window_opacity_label)
                        Settings.Subtitle.Style.Margin -> context.getString(R.string.player_settings_caption_style_margin_label)
                    }

                    is Settings.Subtitle.Style.FontColor -> context.getString(item.stringId)
                    is Settings.Subtitle.Style.Margin -> item.value.toString()

                    is Settings.Subtitle.Style.TextSize -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.FontOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.EdgeStyle -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.BackgroundColor -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.BackgroundOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.WindowColor -> context.getString(item.stringId)

                    is Settings.Subtitle.Style.WindowOpacity -> context.getString(item.stringId)

                    is Settings.Subtitle.OpenSubtitles.Subtitle -> item.openSubtitle.subFileName

                    is Settings.Subtitle.SubDLSubtitles.Subtitle -> item.subDLSubtitle.releaseName ?: item.subDLSubtitle.name

                    is Settings.Speed -> context.getString(item.stringId)

                    is Settings.ExtraBuffering -> context.getString(item.stringId)

                    is Settings.SoftwareDecoder -> context.getString(item.stringId)

                    is Settings.Gestures -> context.getString(item.stringId)

                    is Settings.KeepScreenOn -> context.getString(item.stringId)

                    is Settings.Server -> item.name
                    else -> ""
                }
            }

            binding.tvSettingSubText.apply {
                text = when (item) {
                    is Settings -> when (item) {
                        Settings.Quality -> when (val selected = Settings.Quality.selected) {
                            is Settings.Quality.Auto -> when (val track = selected.currentTrack) {
                                null -> context.getString(R.string.player_settings_quality_auto)
                                else -> context.getString(
                                    R.string.player_settings_quality_auto_selected,
                                    track.height
                                )
                            }
                            is Settings.Quality.VideoTrackInformation -> context.getString(
                                R.string.player_settings_quality,
                                selected.height
                            )
                        }
                        Settings.Audio -> Settings.Audio.selected?.name
                        Settings.Subtitle -> when (val selected = Settings.Subtitle.selected) {
                            is Settings.Subtitle.TextTrackInformation -> selected.label
                            else -> context.getString(R.string.player_settings_subtitles_off)
                        }
                        Settings.Speed -> context.getString(Settings.Speed.selected.stringId)
                        Settings.ExtraBuffering -> context.getString(Settings.ExtraBuffering.selected.stringId)
                        Settings.SoftwareDecoder -> context.getString(Settings.SoftwareDecoder.selected.stringId)
                        Settings.Gestures -> context.getString(Settings.Gestures.selected.stringId)
                        Settings.KeepScreenOn -> context.getString(Settings.KeepScreenOn.selected.stringId)
                        Settings.Server -> Settings.Server.selected?.name ?: ""
                        Settings.ManualZoom -> ""
                        else -> ""
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> context.getString(R.string.player_settings_caption_style_sub_label)
                        Settings.Subtitle.Offset -> context.getString(
                            R.string.player_settings_subtitle_offset_seconds,
                            Settings.Subtitle.Offset.selected.milliseconds / 1_000.0,
                        )
                        is Settings.Subtitle.TextTrackInformation -> item.language ?: ""
                        else -> ""
                    }

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> ""
                        Settings.Subtitle.Style.FontColor -> context.getString(Settings.Subtitle.Style.FontColor.selected.stringId)
                        Settings.Subtitle.Style.TextSize -> context.getString(Settings.Subtitle.Style.TextSize.selected.stringId)
                        Settings.Subtitle.Style.FontOpacity -> context.getString(Settings.Subtitle.Style.FontOpacity.selected.stringId)
                        Settings.Subtitle.Style.EdgeStyle -> context.getString(Settings.Subtitle.Style.EdgeStyle.selected.stringId)
                        Settings.Subtitle.Style.BackgroundColor -> context.getString(Settings.Subtitle.Style.BackgroundColor.selected.stringId)
                        Settings.Subtitle.Style.BackgroundOpacity -> context.getString(Settings.Subtitle.Style.BackgroundOpacity.selected.stringId)
                        Settings.Subtitle.Style.WindowColor -> context.getString(Settings.Subtitle.Style.WindowColor.selected.stringId)
                        Settings.Subtitle.Style.WindowOpacity -> context.getString(Settings.Subtitle.Style.WindowOpacity.selected.stringId)
                        Settings.Subtitle.Style.Margin -> Settings.Subtitle.Style.Margin.selected.value.toString()
                    }

                    is Settings.Subtitle.OpenSubtitles.Subtitle -> item.openSubtitle.languageName

                    is Settings.Subtitle.SubDLSubtitles.Subtitle -> item.subDLSubtitle.lang?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } ?: ""

                    else -> ""
                }
                visibility = when {
                    text.isEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
            }

            binding.ivSettingIsSelected.apply {
                visibility = when (item) {
                    is Settings.Quality -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Audio -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle -> when (item) {
                        is Settings.Subtitle.None -> when {
                            item.isSelected -> View.VISIBLE
                            else -> View.GONE
                        }
                        is Settings.Subtitle.TextTrackInformation -> when {
                            item.isSelected -> View.VISIBLE
                            else -> View.GONE
                        }
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.FontColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.TextSize -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.FontOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.EdgeStyle -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.BackgroundColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.BackgroundOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.WindowColor -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.WindowOpacity -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Style.Margin -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Subtitle.Offset.Value -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Speed -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.ExtraBuffering -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.SoftwareDecoder -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Gestures -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.KeepScreenOn -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    is Settings.Server -> when {
                        item.isSelected -> View.VISIBLE
                        else -> View.GONE
                    }

                    else -> View.GONE
                }
            }

            binding.ivSettingEnter.apply {
                visibility = when (item) {
                    is Settings -> {
                        when(item) {
                            Settings.Quality,
                            Settings.Audio,
                            Settings.Subtitle,
                            Settings.Speed,
                            Settings.ExtraBuffering,
                            Settings.SoftwareDecoder,
                            Settings.Gestures,
                            Settings.KeepScreenOn,
                            Settings.Server -> View.VISIBLE
                            else -> View.GONE
                        }
                    }

                    is Settings.Subtitle -> when (item) {
                        Settings.Subtitle.Style -> View.VISIBLE
                        Settings.Subtitle.Offset -> View.VISIBLE
                        is Settings.Subtitle.None -> View.GONE
                        is Settings.Subtitle.TextTrackInformation -> View.GONE
                        Settings.Subtitle.LocalSubtitles -> View.VISIBLE
                        Settings.Subtitle.OpenSubtitles -> View.VISIBLE
                        Settings.Subtitle.SubDLSubtitles -> View.VISIBLE
                    }

                    is Settings.Subtitle.Style -> when (item) {
                        Settings.Subtitle.Style.ResetStyle -> View.GONE
                        else -> View.VISIBLE
                    }

                    else -> View.GONE
                }
            }
        }
    }
}
