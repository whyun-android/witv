package com.whyun.witv.data;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用内语言切换。
 * <p>
 * 应用的 Activity 继承 FragmentActivity（非 AppCompat），因此不走
 * AppCompatDelegate#setApplicationLocales，而是在 attachBaseContext 中包装 Context。
 * 默认 values/ 为中文，values-en/ 为英文。
 */
public enum AppLanguage {
    /** 跟随系统语言 */
    SYSTEM("system"),
    /** 简体中文 */
    CHINESE("zh"),
    ENGLISH("en");

    private final String id;

    AppLanguage(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static AppLanguage fromId(String id) {
        if (id != null) {
            for (AppLanguage l : values()) {
                if (l.id.equals(id)) {
                    return l;
                }
            }
        }
        return SYSTEM;
    }

    /** 跟随系统时返回 null，表示不覆盖系统 Locale */
    Locale toLocaleOrNull() {
        switch (this) {
            case CHINESE:
                return Locale.SIMPLIFIED_CHINESE;
            case ENGLISH:
                return Locale.ENGLISH;
            case SYSTEM:
            default:
                return null;
        }
    }

    /**
     * 用保存的语言包装 Context，供 Application / Activity 的 attachBaseContext 使用。
     */
    public static Context wrap(Context base) {
        AppLanguage lang = PreferenceManager.readAppLanguage(base);
        Locale locale = lang.toLocaleOrNull();
        if (locale == null) {
            locale = systemLocale();
        }
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(locale));
        }
        Locale.setDefault(locale);
        return base.createConfigurationContext(config);
    }

    /** 当前生效语言是否为中文（用于不走资源的少量文本，如编码描述） */
    public static boolean isChinese(Context context) {
        Configuration c = context.getResources().getConfiguration();
        Locale locale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? c.getLocales().get(0) : c.locale;
        return locale != null && "zh".equals(locale.getLanguage());
    }

    private static Locale systemLocale() {
        Configuration sys = Resources.getSystem().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return sys.getLocales().get(0);
        }
        return sys.locale;
    }
}
