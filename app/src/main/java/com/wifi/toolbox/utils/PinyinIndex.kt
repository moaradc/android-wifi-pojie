package com.wifi.toolbox.utils

import com.github.promeg.pinyinhelper.Pinyin

/**
 * SSID → A-Z 索引分组键（通讯录风格索引栏）。
 *
 * 规则与主流通讯录/城市选择器一致：
 * - 首个 ASCII 字母 → 大写字母（A-Z）
 * - 首个汉字 → 拼音首字母（TinyPinyin，如「张」→ Z）
 * - 首个数字 → '#'（通讯录惯例：数字开头归入 # 组）
 * - 标点/空格/表情等无索引意义的字符 → 跳过继续向后找
 * - 全部无意义 → '#'
 *
 * 依赖：io.github.biezhi:TinyPinyin（原 com.github.promeg:tinypinyin 的
 * Maven Central 重发布，同一 com.github.promeg.pinyinhelper API）。
 */
object PinyinIndex {

    /** 无字母归属的分组键（数字、符号、罕见文字等） */
    const val BUCKET_OTHER = '#'

    /** 索引栏完整字母序列（A-Z + #） */
    val RAIL_LETTERS: List<Char> = ('A'..'Z') + BUCKET_OTHER

    /** SSID → 分组字母 */
    fun sectionKey(ssid: String): Char {
        for (ch in ssid) {
            val code = ch.code
            if (code in 65..90) return ch                     // A-Z
            if (code in 97..122) return ch.uppercaseChar()    // a-z
            if (code in 48..57) return BUCKET_OTHER           // 0-9 → #
            if (Pinyin.isChinese(ch)) {
                val py = Pinyin.toPinyin(ch)
                val first = py.firstOrNull { it in 'A'..'Z' }
                return first ?: BUCKET_OTHER
            }
            // 其它字符（标点/空格/表情/注音…）：跳过继续找
        }
        return BUCKET_OTHER
    }
}
