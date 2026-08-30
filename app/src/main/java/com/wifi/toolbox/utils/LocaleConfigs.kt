package com.wifi.toolbox.utils

import androidx.core.os.LocaleListCompat

object LocaleConfigs {
    data class LocaleItem(
        val tag: String,
        val displayName: String,
        val show: Boolean = true
    )

    val list = listOf(
        LocaleItem("", "跟随系统"),
        LocaleItem("zh-CN", "中文（简体）"),
        LocaleItem("zh-TW", "中文（繁體）"),
        LocaleItem("lzh", "文言（華夏）", false),
        LocaleItem("en", "English"),
        LocaleItem("en-CN", "Chinglish", false)
    )

    fun getLocaleListCompat(index: Int): LocaleListCompat {
        val item = list.getOrNull(index) ?: list[0]
        return if (item.tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(item.tag)
        }
    }
}