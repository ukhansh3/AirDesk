package com.pleos.airdesk

/** Approved two-set Korean composition, local to an app-owned editor, never a system IME. */
class DesktopHangulComposer {
    data class Step(val committed: String, val composing: String)
    internal data class State(val initial: Char?, val vowel: Char?, val final: Char?)
    private var initial: Char? = null
    private var vowel: Char? = null
    private var final: Char? = null

    val active: String get() = render(initial, vowel, final)
    fun reset() { initial = null; vowel = null; final = null }
    internal fun checkpoint() = State(initial, vowel, final)
    internal fun restore(state: State) { initial = state.initial; vowel = state.vowel; final = state.final }

    fun input(jamo: Char): Step {
        require(jamo in INITIALS || jamo in VOWELS)
        var committed = ""
        if (jamo in VOWELS) {
            when {
                vowel == null -> vowel = jamo
                final != null -> {
                    val split = SPLIT_FINAL[final] ?: (null to final!!)
                    committed = render(initial, vowel, split.first)
                    initial = split.second
                    vowel = jamo
                    final = null
                }
                else -> {
                    val joined = JOIN_VOWEL[vowel!! to jamo]
                    if (joined != null) vowel = joined
                    else { committed = active; reset(); vowel = jamo }
                }
            }
        } else {
            when {
                initial == null && vowel == null -> initial = jamo
                initial != null && vowel != null && final == null && jamo in FINALS -> final = jamo
                final != null && JOIN_FINAL[final!! to jamo] != null -> final = JOIN_FINAL[final!! to jamo]
                else -> { committed = active; reset(); initial = jamo }
            }
        }
        return Step(committed, active)
    }

    /** Undo one Korean component, not an entire composed syllable. */
    fun backspace(): String {
        when {
            final != null -> final = SPLIT_FINAL[final]?.first
            vowel != null -> vowel = SPLIT_VOWEL[vowel]
            else -> initial = null
        }
        return active
    }

    companion object {
        private const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        private const val FINALS = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
        private val JOIN_VOWEL = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ', ('ㅡ' to 'ㅣ') to 'ㅢ',
        )
        private val SPLIT_VOWEL = JOIN_VOWEL.entries.associate { it.value to it.key.first }
        private val JOIN_FINAL = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ',
        )
        private val SPLIT_FINAL = JOIN_FINAL.entries.associate { it.value to (it.key.first to it.key.second) }

        private fun render(initial: Char?, vowel: Char?, final: Char?): String {
            if (initial == null) return vowel?.toString() ?: ""
            if (vowel == null) return initial.toString()
            val i = INITIALS.indexOf(initial)
            val v = VOWELS.indexOf(vowel)
            val t = if (final == null) 0 else FINALS.indexOf(final) + 1
            return (0xAC00 + (i * 21 + v) * 28 + t).toChar().toString()
        }

        fun fromKey(code: String, shift: Boolean): Char? {
            if (!code.startsWith("Key") || code.length != 4) return null
            val key = code.last()
            return if (shift) SHIFTED[key] ?: TWO_SET[key] else TWO_SET[key]
        }
        private val TWO_SET = "QWERTYUIOPASDFGHJKLZXCVBNM".zip("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ").toMap()
        private val SHIFTED = mapOf('Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ')
    }
}
