package com.example.livetranslator

import com.google.mlkit.nl.translate.TranslateLanguage

/** 영상 원본 언어: 음성 인식 모델(Vosk) + 번역 언어 코드(ML Kit) */
data class SourceLang(
    val label: String,
    val mlkit: String,
    val model: String,
    val noSpaces: Boolean = false,   // 일본어/중국어는 인식 결과의 띄어쓰기를 제거
) {
    val url get() = "https://alphacephei.com/vosk/models/$model.zip"
    fun clean(s: String) = (if (noSpaces) s.replace(" ", "") else s).trim()
}

object Languages {
    val list = listOf(
        SourceLang("영어", TranslateLanguage.ENGLISH, "vosk-model-small-en-us-0.15"),
        SourceLang("일본어", TranslateLanguage.JAPANESE, "vosk-model-small-ja-0.22", noSpaces = true),
        SourceLang("중국어", TranslateLanguage.CHINESE, "vosk-model-small-cn-0.22", noSpaces = true),
        SourceLang("스페인어", TranslateLanguage.SPANISH, "vosk-model-small-es-0.42"),
        SourceLang("프랑스어", TranslateLanguage.FRENCH, "vosk-model-small-fr-0.22"),
        SourceLang("독일어", TranslateLanguage.GERMAN, "vosk-model-small-de-0.15"),
        SourceLang("러시아어", TranslateLanguage.RUSSIAN, "vosk-model-small-ru-0.22"),
    )
}
