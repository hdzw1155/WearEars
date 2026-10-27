package com.hdz.wearcontrol;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用内语言切换。
 * 只在 Context 上换 Configuration 的 locale, 不重启进程: Activity 用 attachBaseContext,
 * 磁贴服务这类非 Activity 的 Context 用 localizedContext() 拿一份本地化的副本。
 */
final class AppLocale {

    private AppLocale() {
    }

    static Context wrap(Context base) {
        String lang = AppPrefs.lang(base);
        Locale locale = AppPrefs.LANG_EN.equals(lang) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(config);
    }

    static Context localizedContext(Context ctx) {
        String lang = AppPrefs.lang(ctx);
        Locale locale = AppPrefs.LANG_EN.equals(lang) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLocales(new LocaleList(locale));
        return ctx.createConfigurationContext(config);
    }
}
