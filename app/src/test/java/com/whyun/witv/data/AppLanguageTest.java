package com.whyun.witv.data;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class AppLanguageTest {

    @Test
    public void fromId_unknownFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId(null));
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromId("fr"));
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromId("en"));
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromId("zh"));
    }

    @Test
    public void wrap_appliesSavedLanguageToResources() {
        Context app = ApplicationProvider.getApplicationContext();
        PreferenceManager prefs = new PreferenceManager(app);

        prefs.setAppLanguage(AppLanguage.ENGLISH);
        Context en = AppLanguage.wrap(app);
        assertEquals("en", en.getResources().getConfiguration().getLocales().get(0).getLanguage());
        assertFalse(AppLanguage.isChinese(en));

        prefs.setAppLanguage(AppLanguage.CHINESE);
        Context zh = AppLanguage.wrap(app);
        assertEquals("zh", zh.getResources().getConfiguration().getLocales().get(0).getLanguage());
        assertTrue(AppLanguage.isChinese(zh));

        // 恢复默认，避免影响其他测试
        prefs.setAppLanguage(AppLanguage.SYSTEM);
    }
}
