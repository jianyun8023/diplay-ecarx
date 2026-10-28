package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.view.View
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Test fun pickerAndSystemSettingsShareTheSamePreference() {
        AppLocale.save(context, AppLocale.ENGLISH)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertSame(context, AppLocale.wrap(context))
        AppLocale.save(context, AppLocale.SYSTEM)
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun oldPreferenceMigratesOnceAndCannotOverrideLaterSystemChanges() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "en").commit()
        AppLocale.wrap(context)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.wrap(context)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun existingSystemChoiceWinsOverLegacyPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "en").commit()
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        AppLocale.wrap(context)
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
    }

    @Test @Config(sdk = [28, 32])
    fun olderAndroidWrapsEnglishAndReturnsToSystemWithoutChangingGlobalResources() {
        val original = context.resources.configuration.locales.toLanguageTags()
        AppLocale.save(context, AppLocale.ENGLISH)
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale.ENGLISH, wrapped.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_LTR, wrapped.resources.configuration.layoutDirection)
        assertEquals(original, context.resources.configuration.locales.toLanguageTags())
        AppLocale.save(context, AppLocale.SYSTEM)
        assertSame(context, AppLocale.wrap(context))
    }

    @Test fun traditionalChineseUsesItsOwnLocaleOnAndroid13() {
        AppLocale.save(context, AppLocale.TRADITIONAL_CHINESE)
        assertEquals("zh-TW", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.preference(context))
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant-HK")) {
            manager.applicationLocales = LocaleList.forLanguageTags(tag)
            assertEquals(tag, AppLocale.TRADITIONAL_CHINESE, AppLocale.preference(context))
        }
    }

    @Test fun explicitTraditionalScriptIsRecognizedWithoutATraditionalRegion() {
        for (tag in listOf("zh-Hant", "zh-Hant-CN")) {
            manager.applicationLocales = LocaleList.forLanguageTags(tag)
            assertEquals(tag, AppLocale.TRADITIONAL_CHINESE, AppLocale.preference(context))
        }
    }

    @Test fun explicitSimplifiedScriptTakesPrecedenceOverTraditionalRegion() {
        for (tag in listOf("zh-Hans-TW", "zh-Hans-HK", "zh-Hans-MO")) {
            manager.applicationLocales = LocaleList.forLanguageTags(tag)
            assertEquals(tag, AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        }
    }

    @Test fun legacyTraditionalChoiceMigratesOnceWithoutOverridingSystemChoice() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", AppLocale.TRADITIONAL_CHINESE).commit()
        AppLocale.wrap(context)
        assertEquals("zh-TW", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("zh-HK")
        AppLocale.wrap(context)
        assertEquals("zh-HK", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.preference(context))
    }

    @Test @Config(sdk = [28, 29, 30, 31, 32])
    fun traditionalChineseWrapsResourcesOnOlderAndroid() {
        AppLocale.save(context, AppLocale.TRADITIONAL_CHINESE)
        val wrapped = AppLocale.wrap(context)
        assertEquals("zh-TW", wrapped.resources.configuration.locales.toLanguageTags())
        assertEquals("應用程式語言", wrapped.getString(R.string.language_app_language))
    }

    @Test fun pickerContainsOnlySystemEnglishAndChineseVariants() {
        assertEquals(listOf("system", "en", "zh", "zh-TW"), AppLocale.ALL)
        assertEquals("English", AppLocale.displayName(context, AppLocale.ENGLISH))
        assertEquals("简体中文", AppLocale.displayName(context, AppLocale.SIMPLIFIED_CHINESE))
        assertEquals("繁體中文", AppLocale.displayName(context, AppLocale.TRADITIONAL_CHINESE))
        for (removed in listOf("ar", "ru", "es", "uk", "ja", "it")) {
            assertThrows(IllegalArgumentException::class.java) { AppLocale.save(context, removed) }
        }
    }

    @Test fun removedPlatformLocalesReturnToSystemEvenAfterMigration() {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("app_language_platform_migrated", true).commit()
        for (removed in listOf("ar", "ru", "es", "uk", "ja", "it")) {
            manager.applicationLocales = LocaleList.forLanguageTags(removed)
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
            assertSame(context, AppLocale.wrap(context))
            assertTrue(manager.applicationLocales.isEmpty)
        }
    }

    @Test fun unsupportedLegacyPreferencesAreNeverMigrated() {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        for (removed in listOf("ar", "ru", "es", "uk", "ja", "it")) {
            prefs.edit().clear().putString("app_language", removed).commit()
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
            AppLocale.wrap(context)
            assertTrue(manager.applicationLocales.isEmpty)
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
            assertFalse(prefs.contains("app_language"))
        }
    }

    @Test fun removedPlatformChoiceDoesNotResurrectAnOldSupportedPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", AppLocale.TRADITIONAL_CHINESE).commit()
        manager.applicationLocales = LocaleList.forLanguageTags("ja")
        AppLocale.wrap(context)
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test @Config(sdk = [28, 32])
    fun unsupportedLegacyPreferencesUseSystemWithoutChangingGlobalResources() {
        val original = context.resources.configuration.locales.toLanguageTags()
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        for (removed in listOf("ar", "ru", "es", "uk", "ja", "it")) {
            prefs.edit().putString("app_language", removed).commit()
            assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
            assertSame(context, AppLocale.wrap(context))
            assertEquals(original, context.resources.configuration.locales.toLanguageTags())
        }
    }

    @Test @Config(sdk = [28, 32])
    fun systemDefaultKeepsRightToLeftLayoutSupport() {
        AppLocale.save(context, AppLocale.SYSTEM)
        val systemContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("ar"))
                setLayoutDirection(Locale.forLanguageTag("ar"))
            },
        )
        assertSame(systemContext, AppLocale.wrap(systemContext))
        assertEquals(View.LAYOUT_DIRECTION_RTL, systemContext.resources.configuration.layoutDirection)
    }

    @Test @Config(sdk = [28, 32])
    fun systemTraditionalRegionsResolveTraditionalResourcesWithoutAnOverride() {
        AppLocale.save(context, AppLocale.SYSTEM)
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant")) {
            val systemContext = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                },
            )
            assertSame(systemContext, AppLocale.wrap(systemContext))
            assertEquals(tag, "應用程式語言", systemContext.getString(R.string.language_app_language))
        }
    }
}
