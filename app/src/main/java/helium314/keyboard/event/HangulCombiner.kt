// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import java.lang.StringBuilder
import java.util.ArrayList

class HangulCombiner : Combiner {

    private val composingWord = StringBuilder()

    val history: MutableList<HangulSyllable> = mutableListOf()
    private val syllable: HangulSyllable? get() = history.lastOrNull()

    override fun processEvent(previousEvents: ArrayList<Event>?, event: Event): Event {
        if (event.keyCode == KeyCode.SHIFT) return event
        val event = HangulEventDecoder.decodeSoftwareKeyEvent(event)
        if (Character.isWhitespace(event.codePoint)) {
            val text = combiningStateFeedback
            reset()
            return createEventChainFromSequence(text, event)
        } else if (event.isFunctionalKeyEvent) {
            if(event.keyCode == KeyCode.DELETE) {
                return when {
                    history.size == 1 && composingWord.isEmpty() || history.isEmpty() && composingWord.length == 1 -> {
                        reset()
                        Event.createHardwareKeypressEvent(0x20, Constants.CODE_SPACE, 0, event, event.isKeyRepeat)
                    }
                    history.isNotEmpty() -> {
                        history.removeAt(history.lastIndex)
                        Event.createConsumedEvent(event)
                    }
                    composingWord.isNotEmpty() -> {
                        composingWord.deleteCharAt(composingWord.lastIndex)
                        Event.createConsumedEvent(event)
                    }
                    else -> event
                }
            }
            val text = combiningStateFeedback
            reset()
            return createEventChainFromSequence(text, event)
        } else {
            val currentSyllable = syllable ?: HangulSyllable()
            val jamo = HangulJamo.of(event.codePoint)
            if (!event.isCombining || jamo is HangulJamo.NonHangul) {
                composingWord.append(currentSyllable.string)
                composingWord.append(jamo.string)
                history.clear()
            } else {
                when (jamo) {
                    is HangulJamo.Initial -> {
                        if (currentSyllable.initial != null) {
                            val combination = COMBINATION_TABLE_CUSTOM[currentSyllable.initial.codePoint to jamo.codePoint]
                            if (combination != null && currentSyllable.medial == null && currentSyllable.final == null) {
                                history[history.lastIndex] = currentSyllable.copy(initial = HangulJamo.Initial(combination))
                            } else {
                                composingWord.append(currentSyllable.string)
                                history.clear()
                                history += HangulSyllable(initial = jamo)
                            }
                        } else {
                            history += currentSyllable.copy(initial = jamo)
                        }
                    }
                    is HangulJamo.Medial -> {
                        if (currentSyllable.medial != null) {
                            val combination = COMBINATION_TABLE_CUSTOM[currentSyllable.medial.codePoint to jamo.codePoint]
                            if (combination != null && currentSyllable.final == null) {
                                history[history.lastIndex] = currentSyllable.copy(medial = HangulJamo.Medial(combination))
                            } else {
                                composingWord.append(currentSyllable.string)
                                history.clear()
                                history += HangulSyllable(medial = jamo)
                            }
                        } else {
                            history += currentSyllable.copy(medial = jamo)
                        }
                    }
                    is HangulJamo.Final -> {
                        if (currentSyllable.final != null) {
                            val combination = COMBINATION_TABLE_CUSTOM[currentSyllable.final.codePoint to jamo.codePoint]
                            if (combination != null) {
                                history[history.lastIndex] = currentSyllable.copy(final = HangulJamo.Final(combination))
                            } else {
                                composingWord.append(currentSyllable.string)
                                history.clear()
                                history += HangulSyllable(final = jamo)
                            }
                        } else {
                            history += currentSyllable.copy(final = jamo)
                        }
                    }
                    is HangulJamo.Consonant -> {
                        val initial = jamo.toInitial()
                        composingWord.append(currentSyllable.string)
                        history.clear()
                        history += HangulSyllable(initial = initial)
                    }
                    is HangulJamo.Vowel -> {
                        val medial = jamo.toMedial()
                        composingWord.append(currentSyllable.string)
                        history.clear()
                        history += HangulSyllable(medial = medial)
                    }
                    is HangulJamo.NonHangul -> Unit
                }
            }
        }
        return Event.createConsumedEvent(event)
    }

    override val combiningStateFeedback: CharSequence
        get() = composingWord.toString() + (syllable?.string ?: "")

    override fun reset() {
        composingWord.setLength(0)
        history.clear()
    }

    sealed class HangulJamo {
        abstract val codePoint: Int
        abstract val modern: Boolean
        val string: String get() = codePoint.toChar().toString()
        data class NonHangul(override val codePoint: Int) : HangulJamo() {
            override val modern: Boolean get() = false
        }
        data class Initial(override val codePoint: Int) : HangulJamo() {
            override val modern: Boolean get() = codePoint in 0x1100 .. 0x1112
            val ordinal: Int get() = codePoint - 0x1100
            fun toConsonant(): Consonant? {
                val codePoint = COMPAT_CONSONANTS.getOrNull(CONVERT_INITIALS.indexOf(codePoint.toChar())) ?: return null
                if(codePoint.code == 0) return null
                return Consonant(codePoint.code)
            }
        }
        data class Medial(override val codePoint: Int) : HangulJamo() {
            override val modern: Boolean get() = codePoint in 1161 .. 0x1175
            val ordinal: Int get() = codePoint - 0x1161
            fun toVowel(): Vowel? {
                val codePoint = COMPAT_VOWELS.getOrNull(CONVERT_MEDIALS.indexOf(codePoint.toChar())) ?: return null
                return Vowel(codePoint.code)
            }
        }
        data class Final(override val codePoint: Int, val combinationPair: Pair<Int, Int>? = null) : HangulJamo() {
            override val modern: Boolean get() = codePoint in 0x11a8 .. 0x11c2
            val ordinal: Int get() = codePoint - 0x11a7
            fun toConsonant(): Consonant? {
                val codePoint = COMPAT_CONSONANTS.getOrNull(CONVERT_FINALS.indexOf(codePoint.toChar())) ?: return null
                if(codePoint.code == 0) return null
                return Consonant(codePoint.code)
            }
        }
        data class Consonant(override val codePoint: Int) : HangulJamo() {
            override val modern: Boolean get() = codePoint in 0x3131 .. 0x314e
            val ordinal: Int get() = codePoint - 0x3131
            fun toInitial(): Initial? {
                val codePoint = CONVERT_INITIALS.getOrNull(COMPAT_CONSONANTS.indexOf(codePoint.toChar())) ?: return null
                if(codePoint.code == 0) return null
                return Initial(codePoint.code)
            }
            fun toFinal(): Final? {
                val codePoint = CONVERT_FINALS.getOrNull(COMPAT_CONSONANTS.indexOf(codePoint.toChar())) ?: return null
                if(codePoint.code == 0) return null
                return Final(codePoint.code)
            }
        }
        data class Vowel(override val codePoint: Int) : HangulJamo() {
            override val modern: Boolean get() = codePoint in 0x314f .. 0x3163
            val ordinal: Int get() = codePoint - 0x314f1
            fun toMedial(): Medial? {
                val codePoint = CONVERT_MEDIALS.getOrNull(COMPAT_VOWELS.indexOf(codePoint.toChar())) ?: return null
                if(codePoint.code == 0) return null
                return Medial(codePoint.code)
            }
        }
        companion object {
            const val COMPAT_CONSONANTS = "ㄱㄲㄳㄴㄵㄶㄷㄸㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅃㅄㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
            const val COMPAT_VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
            const val CONVERT_INITIALS = "ᄀᄁ\u0000ᄂ\u0000\u0000ᄃᄄᄅ\u0000\u0000\u0000\u0000\u0000\u0000\u0000ᄆᄇᄈ\u0000ᄉᄊᄋᄌᄍᄎᄏᄐᄑᄒ"
            const val CONVERT_MEDIALS = "ᅡᅢᅣᅤᅥᅦᅧᅨᅩᅪᅫᅬᅭᅮᅯᅰᅱᅲᅳᅴᅵ"
            const val CONVERT_FINALS = "ᆨᆩᆪᆫᆬᆭᆮ\u0000ᆯᆰᆱᆲᆳᆴᆵᆶᆷᆸ\u0000ᆹᆺᆻᆼᆽ\u0000ᆾᆿᇀᇁᇂ"
            fun of(codePoint: Int): HangulJamo {
                return when(codePoint) {
                    in 0x3131 .. 0x314e -> Consonant(codePoint)
                    in 0x314f .. 0x3163 -> Vowel(codePoint)
                    in 0x1100 .. 0x115f -> Initial(codePoint)
                    in 0x1160 .. 0x11a7 -> Medial(codePoint)
                    in 0x11a8 .. 0x11ff -> Final(codePoint)
                    else -> NonHangul(codePoint)
                }
            }
        }
    }

    data class HangulSyllable(
            val initial: HangulJamo.Initial? = null,
            val medial: HangulJamo.Medial? = null,
            val final: HangulJamo.Final? = null
    ) {
        val combinable: Boolean get() = (initial?.modern ?: false) && (medial?.modern ?: false) && (final?.modern ?: true)
        val combined: String get() = (0xac00 + (initial?.ordinal ?: 0) * 21 * 28
                + (medial?.ordinal ?: 0) * 28
                + (final?.ordinal ?: 0)).toChar().toString()
        val uncombined: String get() = (initial?.string ?: "") + (medial?.string ?: "") + (final?.string ?: "")
        val uncombinedCompat: String get() = (initial?.toConsonant()?.string ?: "") +
                (medial?.toVowel()?.string ?: "") + (final?.toConsonant()?.string ?: "")
        val string: String get() = if (this.combinable) this.combined else this.uncombinedCompat
    }

    companion object {
        val COMBINATION_TABLE_CUSTOM = mutableMapOf<Pair<Int, Int>, Int>().apply {
            // Initial
            listOf(0x1100 to 0x1103 to 0x1112, 0x1107 to 0x110C to 0x110A, 0x110B to 0x1100 to 0x110F, 0x110B to 0x1103 to 0x1110,
                   0x110B to 0x1107 to 0x1111, 0x110B to 0x110C to 0x110D, 0x1109 to 0x1100 to 0x1101, 0x1109 to 0x1103 to 0x1104,
                   0x1109 to 0x1107 to 0x1108, 0x1109 to 0x110C to 0x110E).forEach {
                put(it.first.first to it.first.second, it.second)
                put(it.first.second to it.first.first, it.second)
            }
            // Medial
            listOf(0x1173 to 0x1175 to 0x1174, 0x1173 to 0x1162 to 0x1166, 0x1175 to 0x1162 to 0x1164, 0x1164 to 0x1173 to 0x1168,
                   0x1166 to 0x1175 to 0x1168, 0x1174 to 0x1162 to 0x1168, 0x1169 to 0x1175 to 0x116C, 0x1169 to 0x1162 to 0x116D,
                   0x116C to 0x1162 to 0x116B, 0x116D to 0x1175 to 0x116B, 0x1164 to 0x1169 to 0x116B, 0x116E to 0x1175 to 0x1171,
                   0x116E to 0x1162 to 0x1172, 0x1171 to 0x1162 to 0x1170, 0x1172 to 0x1175 to 0x1170, 0x1164 to 0x116E to 0x1170,
                   0x1161 to 0x1162 to 0x1163, 0x1165 to 0x1162 to 0x1167).forEach {
                put(it.first.first to it.first.second, it.second)
                put(it.first.second to it.first.first, it.second)
            }
            // Final
            listOf(0x11BC to 0x11AB to 0x11AD, 0x11BC to 0x11AF to 0x11B6, 0x11BC to 0x11A8 to 0x11BF, 0x11BC to 0x11AE to 0x11C0,
                   0x11BC to 0x11B8 to 0x11C1, 0x11B7 to 0x11AB to 0x11C2, 0x11B7 to 0x11AF to 0x11B1, 0x11B7 to 0x11A8 to 0x11B0,
                   0x11B7 to 0x11AE to 0x11B4, 0x11B7 to 0x11B8 to 0x11A9, 0x11BA to 0x11AB to 0x11BB, 0x11BA to 0x11AF to 0x11B3,
                   0x11BA to 0x11A8 to 0x11AA, 0x11BA to 0x11B8 to 0x11B9, 0x11BA to 0x11AE to 0x11B5, 0x11B8 to 0x11AF to 0x11B2,
                   0x11AE to 0x11A8 to 0x11B2, 0x11B2 to 0x11BC to 0x11BD, 0x11C1 to 0x11AF to 0x11BD, 0x11B6 to 0x11B8 to 0x11BD,
                   0x11B2 to 0x11BA to 0x11AC, 0x11B9 to 0x11AF to 0x11AC, 0x11B3 to 0x11B8 to 0x11AC, 0x11B2 to 0x11B7 to 0x11BE,
                   0x11B4 to 0x11A8 to 0x11BE, 0x11B0 to 0x11AE to 0x11BE).forEach {
                put(it.first.first to it.first.second, it.second)
                put(it.first.second to it.first.first, it.second)
            }
        }.toMap()
        private fun createEventChainFromSequence(text: CharSequence, originalEvent: Event): Event {
            return Event.createSoftwareTextEvent(text, KeyCode.MULTIPLE_CODE_POINTS, originalEvent)
        }
    }

}
