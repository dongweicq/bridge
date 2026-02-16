package com.bridge.util

/**
 * 拼音工具类
 * 将中文转换为拼音首字母
 */
object PinyinUtil {

    // 简单的拼音首字母映射表（覆盖常用汉字）
    // 基于 Unicode 编码范围
    private val PINYIN_INITIALS = mapOf(
        // A
        '阿' to 'a', '啊' to 'a', '安' to 'a', '爱' to 'a', '按' to 'a',
        // B
        '八' to 'b', '百' to 'b', '北' to 'b', '本' to 'b', '边' to 'b',
        '表' to 'b', '别' to 'b', '并' to 'b', '不' to 'b', '步' to 'b',
        // C
        '才' to 'c', '成' to 'c', '出' to 'c', '从' to 'c', '存' to 'c',
        '错' to 'c', '长' to 'c', '城' to 'c', '传' to 'c', '次' to 'c',
        '输' to 's', // 输
        // D
        '大' to 'd', '到' to 'd', '的' to 'd', '地' to 'd', '点' to 'd',
        '电' to 'd', '东' to 'd', '动' to 'd', '都' to 'd', '对' to 'd',
        // E
        '而' to 'e', '二' to 'e', '儿' to 'e',
        // F
        '发' to 'f', '法' to 'f', '方' to 'f', '非' to 'f', '分' to 'f',
        '风' to 'f', '服' to 'f', '复' to 'f', '父' to 'f', '负' to 'f',
        // G
        '改' to 'g', '高' to 'g', '个' to 'g', '给' to 'g', '公' to 'g',
        '工' to 'g', '关' to 'g', '国' to 'g', '过' to 'g', '告' to 'g',
        // H
        '还' to 'h', '海' to 'h', '好' to 'h', '和' to 'h', '黑' to 'h',
        '很' to 'h', '红' to 'h', '后' to 'h', '会' to 'h', '活' to 'h',
        // I (没有以i开头的拼音)
        // J
        '机' to 'j', '基' to 'j', '几' to 'j', '家' to 'j', '见' to 'j',
        '将' to 'j', '教' to 'j', '接' to 'j', '进' to 'j', '就' to 'j',
        '军' to 'j', '据' to 'j', '局' to 'j', '件' to 'j', '己' to 'j',
        // K
        '开' to 'k', '看' to 'k', '可' to 'k', '空' to 'k', '口' to 'k',
        '快' to 'k', '块' to 'k', '况' to 'k', '困' to 'k', '科' to 'k',
        // L
        '来' to 'l', '老' to 'l', '了' to 'l', '里' to 'l', '理' to 'l',
        '力' to 'l', '立' to 'l', '利' to 'l', '联' to 'l', '两' to 'l',
        '路' to 'l', '律' to 'l', '流' to 'l', '六' to 'l', '龙' to 'l',
        // M
        '马' to 'm', '没' to 'm', '美' to 'm', '门' to 'm', '面' to 'm',
        '名' to 'm', '明' to 'm', '命' to 'm', '目' to 'm', '每' to 'm',
        // N
        '那' to 'n', '能' to 'n', '你' to 'n', '年' to 'n', '内' to 'n',
        '南' to 'n', '难' to 'n', '呢' to 'n', '牛' to 'n', '农' to 'n',
        // O
        '哦' to 'o',
        // P
        '怕' to 'p', '派' to 'p', '平' to 'p', '品' to 'p', '破' to 'p',
        '普' to 'p', '判' to 'p', '盘' to 'p', '片' to 'p', '票' to 'p',
        // Q
        '七' to 'q', '起' to 'q', '气' to 'q', '前' to 'q', '且' to 'q',
        '请' to 'q', '去' to 'q', '全' to 'q', '确' to 'q', '亲' to 'q',
        '其' to 'q', '期' to 'q', '情' to 'q', '区' to 'q', '权' to 'q',
        // R
        '然' to 'r', '让' to 'r', '人' to 'r', '任' to 'r', '日' to 'r',
        '容' to 'r', '入' to 'r', '如' to 'r', '热' to 'r', '认' to 'r',
        // S
        '三' to 's', '上' to 's', '少' to 's', '设' to 's', '社' to 's',
        '生' to 's', '师' to 's', '十' to 's', '时' to 's', '实' to 's',
        '事' to 's', '受' to 's', '书' to 's', '水' to 's', '说' to 's',
        '思' to 's', '死' to 's', '四' to 's', '送' to 's', '搜' to 's',
        // T
        '他' to 't', '台' to 't', '太' to 't', '谈' to 't', '天' to 't',
        '条' to 't', '通' to 't', '同' to 't', '头' to 't', '图' to 't',
        '土' to 't', '推' to 't', '她' to 't', '特' to 't', '提' to 't',
        // U (没有以u开头的拼音)
        // V (v用于ü)
        // W
        '外' to 'w', '完' to 'w', '万' to 'w', '王' to 'w', '网' to 'w',
        '为' to 'w', '文' to 'w', '我' to 'w', '无' to 'w', '五' to 'w',
        '物' to 'w', '务' to 'w', '问' to 'w', '闻' to 'w', '文' to 'w',
        '微' to 'w', '位' to 'w', '晚' to 'w', '往' to 'w', '维' to 'w',
        // X
        '西' to 'x', '系' to 'x', '下' to 'x', '先' to 'x', '现' to 'x',
        '相' to 'x', '想' to 'x', '向' to 'x', '小' to 'x', '校' to 'x',
        '些' to 'x', '心' to 'x', '新' to 'x', '信' to 'x', '行' to 'x',
        '学' to 'x', '讯' to 'x', '息' to 'x', '修' to 'x', '选' to 'x',
        // Y
        '研' to 'y', '言' to 'y', '眼' to 'y', '要' to 'y', '也' to 'y',
        '一' to 'y', '以' to 'y', '意' to 'y', '因' to 'y', '引' to 'y',
        '应' to 'y', '有' to 'y', '又' to 'y', '于' to 'y', '与' to 'y',
        '语' to 'y', '元' to 'y', '月' to 'y', '员' to 'y', '原' to 'y',
        // Z
        '在' to 'z', '早' to 'z', '则' to 'z', '怎' to 'z', '张' to 'z',
        '真' to 'z', '正' to 'z', '知' to 'z', '之' to 'z', '只' to 'z',
        '中' to 'z', '种' to 'z', '重' to 'z', '主' to 'z', '着' to 'z',
        '自' to 'z', '子' to 'z', '字' to 'z', '走' to 'z', '最' to 'z',
        '作' to 'z', '做' to 'z', '座' to 'z', '组' to 'z', '助' to 'z',
        '手' to 'z', // 助手
    )

    /**
     * 将中文转换为拼音首字母
     * @param text 中文文本
     * @return 拼音首字母（小写）
     */
    fun toPinyinInitials(text: String): String {
        val result = StringBuilder()
        for (char in text) {
            when {
                // 字母和数字直接保留
                char.isLetterOrDigit() && char.code < 128 -> {
                    result.append(char.lowercaseChar())
                }
                // 查找映射表
                PINYIN_INITIALS.containsKey(char) -> {
                    result.append(PINYIN_INITIALS[char])
                }
                // 其他中文字符，使用Unicode范围估算
                char.code in 0x4E00..0x9FA5 -> {
                    val initial = getPinyinInitialByUnicode(char)
                    result.append(initial)
                }
                // 空格和特殊字符跳过
                !char.isWhitespace() -> {
                    // 保留其他字符
                }
            }
        }
        return result.toString()
    }

    /**
     * 根据Unicode编码范围估算拼音首字母
     * 这是一个简化的实现，覆盖大部分常用字
     */
    private fun getPinyinInitialByUnicode(char: Char): Char {
        val code = char.code
        return when {
            code in 0x4E00..0x4E54 -> 'a'
            code in 0x4E55..0x4EFF -> 'a'
            code in 0x5000..0x50FF -> 'b'
            code in 0x5100..0x51FF -> 'b'
            code in 0x5200..0x52FF -> 'b'
            code in 0x5300..0x53FF -> 'c'
            code in 0x5400..0x54FF -> 'c'
            code in 0x5500..0x55FF -> 'c'
            code in 0x5600..0x56FF -> 'd'
            code in 0x5700..0x57FF -> 'd'
            code in 0x5800..0x58FF -> 'd'
            code in 0x5900..0x59FF -> 'e'
            code in 0x5A00..0x5AFF -> 'f'
            code in 0x5B00..0x5BFF -> 'f'
            code in 0x5C00..0x5CFF -> 'g'
            code in 0x5D00..0x5DFF -> 'g'
            code in 0x5E00..0x5EFF -> 'h'
            code in 0x5F00..0x5FFF -> 'h'
            code in 0x6000..0x60FF -> 'j'
            code in 0x6100..0x61FF -> 'j'
            code in 0x6200..0x62FF -> 'k'
            code in 0x6300..0x63FF -> 'l'
            code in 0x6400..0x64FF -> 'l'
            code in 0x6500..0x65FF -> 'm'
            code in 0x6600..0x66FF -> 'm'
            code in 0x6700..0x67FF -> 'n'
            code in 0x6800..0x68FF -> 'o'
            code in 0x6900..0x69FF -> 'p'
            code in 0x6A00..0x6AFF -> 'q'
            code in 0x6B00..0x6BFF -> 'q'
            code in 0x6C00..0x6CFF -> 'r'
            code in 0x6D00..0x6DFF -> 's'
            code in 0x6E00..0x6EFF -> 's'
            code in 0x6F00..0x6FFF -> 't'
            code in 0x7000..0x70FF -> 't'
            code in 0x7100..0x71FF -> 'w'
            code in 0x7200..0x72FF -> 'w'
            code in 0x7300..0x73FF -> 'x'
            code in 0x7400..0x74FF -> 'x'
            code in 0x7500..0x75FF -> 'y'
            code in 0x7600..0x76FF -> 'y'
            code in 0x7700..0x77FF -> 'z'
            code in 0x7800..0x78FF -> 'z'
            else -> 'z'
        }
    }
}
