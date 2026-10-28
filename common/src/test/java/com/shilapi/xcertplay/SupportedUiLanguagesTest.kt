package com.shilapi.xcertplay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's picker, Android declaration and shipped translations share one supported scope. */
class SupportedUiLanguagesTest {
    @Test fun pickerOffersOnlySystemEnglishAndTheTwoChineseScripts() {
        assertEquals(listOf("system", "en", "zh", "zh-TW"), AppLocale.ALL)
    }

    @Test fun androidDeclaresOnlyTheThreeSupportedLanguages() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml"))
        val locales = document.getElementsByTagName("locale")
        val names = (0 until locales.length).map {
            locales.item(it).attributes.getNamedItem("android:name").nodeValue
        }
        assertEquals(listOf("en", "zh-CN", "zh-TW"), names)
    }

    @Test fun noRemovedTranslationDirectoriesRemainInAnyAppModule() {
        for (module in listOf(".", "../shared", "../mobile", "../automotive", "../samples/home", "../samples/maphost")) {
            val folders = File(module, "src/main/res").listFiles().orEmpty().filter {
                it.isDirectory && it.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?"))
            }.map { it.name }.toSet()
            assertEquals(module, emptySet<String>(), folders - setOf("values-zh-rCN", "values-zh-rTW"))
        }
    }
}
