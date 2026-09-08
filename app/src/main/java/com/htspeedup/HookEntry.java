package com.htspeedup;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "HT_Speedup";

    private static final String[] BLOCK_PATHS = {
        "shortflix_api", "livehub/channel_list", "get_thirtysix_question",
        "language_partner/interested", "gift_shop", "resource_pre_load",
        "free_recommend_status", "is_refresh_netease_token",
        "quality_copywriting_group", "movie/risk/list",
        "notification-settings", "ali_log_token", "report_logic",
        "online_status", "focus_info", "chat_plan", "emoji_rain",
        "vip_gift", "send_gift", "closed_friend", "bubble_tips",
        "voice_input", "translate_config", "get_pay_chat_info",
        "livehub/user/status", "get_latest_chat_plans",
        "chat_list_banner", "vip_trial/banner",
        "login_config/business/vip_product", "chat_magic_wand",
        "click_word_magic_wand", "publishing_skills", "vip_page_banner",
        "vip_page_content", "vip_info", "vip_page", "vip_banner",
        "vip_privilege_config", "vip_status_report", "content_paywall",
        "nobility_birthday", "product_list", "virtual_pay", "learn_tab",
        "system_notice", "mnt_info", "recommender_follow",
        "moment_tab_info", "list_op_uid", "query_expose_record",
        "post_recommend_btn", "like_popup", "newbie_task",
        "get_user_sealing", "ip_info", "guest_config", "launch_config",
        "platform/banner", "top_menu", "htserver/report", "sdkcs/verify",
        "wns_config", "livehub/live_voice", "live_voice/cfg",
        "multi_lang", "ht_advert", "rewarded_advert",
        "login_config/business/advert", "v_cube.license", "meme/user",
        "meme/batch_detail", "moment/notify", "exchange_list",
        "chat_assist", "translate/v2/config", "translate/v2/sts",
        "cards", "magic_wand/main", "get_course_module",
        "profile_banner", "learn_record", "user_virtual_info",
        "settle_center"
    };

    private static volatile long lastToUserChatTs = 0;
    private static final long TO_USER_CHAT_DEDUP_MS = 10_000;

    private static final ThreadLocal<Integer> CHAT_USER_ID =
        new ThreadLocal<>();

    private static final ConcurrentHashMap<Integer, Long>
        userinfoQueryRecord = new ConcurrentHashMap<>();

    private static final long USERINFO_QUERY_INTERVAL_MS = 20_000;

    /*
     * 主界面 MainTabV3Activity 是否已经进入过前台。
     *
     * false：第一次冷启动
     * true：主界面已经显示过，后续 SplashAdActivity 属于热恢复
     */
    private static final AtomicBoolean MAIN_ACTIVITY_WAS_RESUMED =
        new AtomicBoolean(false);

    /*
     * 是否连冷启动的广告 Splash 也一起跳过。
     *
     * false：冷启动保留广告 Splash（默认，符合你的要求）
     * true：冷启动也跳过，进入主界面更快，但不会显示广告开屏
     */
    private static final boolean SKIP_COLD_SPLASH_AD = false;

    private static final XC_MethodHook NOOP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    @Override
    public void handleLoadPackage(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        if (!"com.hellotalk".equals(lpp.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " ===== 模块开始加载 =====");

        hookSkipSplashAd(lpp);
        hookUserInfoProviderLoad(lpp);
        hookNetworkTimeoutToastSuppression(lpp);
        hookTitleController(lpp);
        hookChatDetailFragment(lpp);
        hookNewCall(lpp);
        hookRealCall(lpp);
        hookChatPage(lpp);

        XposedBridge.log(TAG + " ===== 钩子安装完成 =====");
    }

    /**
     * 核心修复。
     *
     * 逆向日志证明：
     *
     * 热恢复时 HelloTalk 会重新启动广告 Splash 页：
     *
     * com.hellotalk.lib.ad.core.display.splash.SplashAdActivity
     *
     * 流程：
     * MainTabV3Activity.onResume
     *     -> 启动 SplashAdActivity
     *     -> MainTabV3Activity.onPause
     *     -> SplashAdActivity 显示约 1.2 秒
     *     -> SplashAdActivity.onPause
     *     -> MainTabV3Activity.onResume
     *
     * 广告没加载出来时，SplashAdActivity 显示默认 HelloTalk 大图标。
     *
     * 处理：
     * 主界面已经进入过前台以后，SplashAdActivity 再创建时直接 finish。
     * 冷启动时保留，不影响正常启动流程。
     */
    private void hookSkipSplashAd(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            final Class<?> mainActivity = XposedHelpers.findClass(
                "com.hellotalk.lib.main.home.ui.MainTabV3Activity",
                lpp.classLoader
            );

            final Class<?> splashAdActivity = XposedHelpers.findClass(
                "com.hellotalk.lib.ad.core.display.splash.SplashAdActivity",
                lpp.classLoader
            );

            /*
             * 记录主界面已经进入过前台。
             */
            XposedHelpers.findAndHookMethod(
                mainActivity,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                        MethodHookParam param
                    ) {
                        MAIN_ACTIVITY_WAS_RESUMED.set(true);

                        XposedBridge.log(
                            TAG
                                + " MainTabV3Activity 已进入前台，"
                                + "后续 SplashAd 按热恢复处理"
                        );
                    }
                }
            );

            /*
             * 热恢复时跳过 SplashAdActivity。
             */
            XposedHelpers.findAndHookMethod(
                splashAdActivity,
                "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                        MethodHookParam param
                    ) {
                        boolean hotResume =
                            MAIN_ACTIVITY_WAS_RESUMED.get();

                        boolean skip =
                            hotResume || SKIP_COLD_SPLASH_AD;

                        if (!skip) {
                            XposedBridge.log(
                                TAG
                                    + " 冷启动：保留 SplashAdActivity"
                            );
                            return;
                        }

                        try {
                            android.app.Activity activity =
                                (android.app.Activity)
                                    param.thisObject;

                            activity.finish();

                            /*
                             * 跳过原始 onCreate，避免它继续初始化广告。
                             */
                            param.setResult(null);

                            XposedBridge.log(
                                TAG
                                    + (hotResume
                                        ? " 热恢复：已跳过 SplashAdActivity"
                                        : " 已跳过 SplashAdActivity")
                            );
                        } catch (Throwable t) {
                            XposedBridge.log(
                                TAG
                                    + " 跳过 SplashAdActivity 失败: "
                                    + t.getMessage()
                            );
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook SplashAdActivity 跳过安装成功"
            );
        } catch (Throwable t) {
            XposedBridge.log(
                TAG
                    + " hook SplashAdActivity 失败: "
                    + t.getMessage()
            );
        }
    }

    private void hookNetworkTimeoutToastSuppression(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            Class<?> gm6Class = XposedHelpers.findClass(
                "gm6",
                lpp.classLoader
            );

            XposedBridge.hookAllMethods(
                gm6Class,
                "o",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                        MethodHookParam param
                    ) {
                        try {
                            if (param.args == null
                                || param.args.length < 1) {
                                return;
                            }

                            Object resIdObject = param.args[0];

                            if (!(resIdObject instanceof Integer)) {
                                return;
                            }

                            int resId = (Integer) resIdObject;

                            if (resId == 0x7f141387) {
                                param.setResult(null);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook gm6.o 网络超时黑框拦截成功"
            );
        } catch (Throwable t) {
            XposedBridge.log(
                TAG + " hook gm6.o 失败: "
                    + t.getMessage()
            );
        }
    }

    private void hookUserInfoProviderLoad(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            final Class<?> yrvClass = XposedHelpers.findClass(
                "yrv",
                lpp.classLoader
            );

            XposedBridge.hookAllMethods(
                yrvClass,
                "b",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                        MethodHookParam param
                    ) {
                        try {
                            if (param.args == null
                                || param.args.length < 5) {
                                return;
                            }

                            Object userIdObject = param.args[0];

                            if (!(userIdObject instanceof Integer)) {
                                return;
                            }

                            int userId = (Integer) userIdObject;

                            if (hasEnoughCache(yrvClass, userId)) {
                                Object cachedData =
                                    getCachedUserInfo(
                                        yrvClass,
                                        userId
                                    );

                                if (cachedData != null) {
                                    param.setResult(cachedData);
                                    return;
                                }
                            }

                            long now = System.currentTimeMillis();
                            Long lastQuery =
                                userinfoQueryRecord.get(userId);

                            if (lastQuery != null
                                && now - lastQuery
                                    < USERINFO_QUERY_INTERVAL_MS) {
                                Object cachedData =
                                    getCachedUserInfo(
                                        yrvClass,
                                        userId
                                    );

                                if (cachedData != null) {
                                    param.setResult(cachedData);
                                    return;
                                }
                            }

                            userinfoQueryRecord.put(userId, now);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook UserInfoProvider.b() 成功"
            );
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasEnoughCache(
        Class<?> yrvClass,
        int userId
    ) {
        try {
            Object provider =
                XposedHelpers.getStaticObjectField(
                    yrvClass,
                    "a"
                );

            if (provider == null) {
                return false;
            }

            Object cache =
                XposedHelpers.getObjectField(
                    provider,
                    "d"
                );

            if (cache == null) {
                return false;
            }

            Object cachedData =
                XposedHelpers.callMethod(
                    cache,
                    "c",
                    (Integer) userId
                );

            if (cachedData == null) {
                return false;
            }

            Object baseInfo =
                XposedHelpers.callMethod(
                    cachedData,
                    "d"
                );

            Object userOnline =
                XposedHelpers.callMethod(
                    cachedData,
                    "u"
                );

            return baseInfo != null && userOnline != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getCachedUserInfo(
        Class<?> yrvClass,
        int userId
    ) {
        try {
            Object provider =
                XposedHelpers.getStaticObjectField(
                    yrvClass,
                    "a"
                );

            if (provider == null) {
                return null;
            }

            Object cache =
                XposedHelpers.getObjectField(
                    provider,
                    "d"
                );

            if (cache == null) {
                return null;
            }

            return XposedHelpers.callMethod(
                cache,
                "c",
                (Integer) userId
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookTitleController(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            Class<?> pitClass = XposedHelpers.findClass(
                "pit",
                lpp.classLoader
            );

            XposedBridge.hookAllMethods(
                pitClass,
                "M",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                        MethodHookParam param
                    ) {
                        try {
                            Object controller = param.thisObject;

                            Object userOnline =
                                XposedHelpers.getObjectField(
                                    controller,
                                    "i"
                                );

                            Object baseInfo =
                                XposedHelpers.getObjectField(
                                    controller,
                                    "k"
                                );

                            if (userOnline != null
                                && baseInfo != null) {
                                param.setResult(null);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook Lpit.M() 成功"
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookChatDetailFragment(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            Class<?> fragClass = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.fragment.ChatDetailFragment",
                lpp.classLoader
            );

            XposedBridge.hookAllMethods(
                fragClass,
                "setArguments",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(
                        MethodHookParam param
                    ) {
                        try {
                            if (param.args == null
                                || param.args.length == 0) {
                                return;
                            }

                            android.os.Bundle bundle =
                                (android.os.Bundle) param.args[0];

                            if (bundle == null) {
                                return;
                            }

                            int userId =
                                bundle.getInt(
                                    "user_id",
                                    0
                                );

                            if (userId > 0) {
                                CHAT_USER_ID.set(userId);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook ChatDetailFragment 成功"
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookNewCall(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            Class<?> clientClass = XposedHelpers.findClass(
                "okhttp3.OkHttpClient",
                lpp.classLoader
            );

            XposedBridge.hookAllMethods(
                clientClass,
                "newCall",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(
                        MethodHookParam param
                    ) {
                        try {
                            if (param.args == null
                                || param.args.length == 0) {
                                return;
                            }

                            String url =
                                getUrlFromRequest(
                                    param.args[0]
                                );

                            if (url != null
                                && shouldBlockUrl(url)) {
                                param.setThrowable(
                                    new IOException(
                                        TAG + " blocked"
                                    )
                                );
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            );

            XposedBridge.log(
                TAG + " hook newCall 成功"
            );
        } catch (Throwable ignored) {
        }
    }

    private static boolean shouldBlockUrl(
        String url
    ) {
        if (url == null) {
            return false;
        }

        if (url.contains("ht_im/sock")) {
            return false;
        }

        if (url.contains("p2p-chat/to-user-chat")) {
            long now = System.currentTimeMillis();

            if (now - lastToUserChatTs
                < TO_USER_CHAT_DEDUP_MS) {
                return true;
            }

            lastToUserChatTs = now;
            return false;
        }

        for (String path : BLOCK_PATHS) {
            if (url.contains(path)) {
                return true;
            }
        }

        return false;
    }

    private void hookRealCall(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        Class<?> realCall = null;

        String[] candidates = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall"
        };

        for (String name : candidates) {
            try {
                realCall = XposedHelpers.findClass(
                    name,
                    lpp.classLoader
                );
                break;
            } catch (Throwable ignored) {
            }
        }

        if (realCall == null) {
            return;
        }

        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(
                MethodHookParam param
            ) {
                try {
                    String url =
                        getUrlFromRealCall(
                            param.thisObject
                        );

                    if (url != null
                        && shouldBlockUrl(url)) {
                        blockRequest(param);
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            XposedBridge.hookAllMethods(
                realCall,
                "enqueue",
                hook
            );

            XposedBridge.log(
                TAG + " hook RealCall.enqueue 成功"
            );
        } catch (Throwable ignored) {
        }
    }

    private void hookChatPage(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            Class<?> frag = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.fragment.ChatDetailFragment",
                lpp.classLoader
            );

            hookVoidMethod(frag, "T3");
            hookVoidMethod(frag, "R3");

            try {
                XposedHelpers.findAndHookMethod(
                    frag,
                    "S3",
                    boolean.class,
                    NOOP_HOOK
                );
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(
                    frag,
                    "F3",
                    boolean.class,
                    NOOP_HOOK
                );
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> vm = XposedHelpers.findClass(
                "ha4",
                lpp.classLoader
            );

            try {
                Class<?> tc2 = XposedHelpers.findClass(
                    "tc2",
                    lpp.classLoader
                );

                XposedHelpers.findAndHookMethod(
                    vm,
                    "R",
                    tc2,
                    NOOP_HOOK
                );
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(
                    vm,
                    "P",
                    int.class,
                    int.class,
                    NOOP_HOOK
                );
            } catch (Throwable ignored) {
            }

            try {
                XposedHelpers.findAndHookMethod(
                    vm,
                    "A",
                    java.util.List.class,
                    NOOP_HOOK
                );
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookVoidMethod(
        Class<?> clazz,
        String name
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                name,
                NOOP_HOOK
            );
        } catch (Throwable ignored) {
        }
    }

    private static void blockRequest(
        XC_MethodHook.MethodHookParam param
    ) {
        try {
            if (param.args != null
                && param.args.length > 0) {
                XposedHelpers.callMethod(
                    param.args[0],
                    "onFailure",
                    param.thisObject,
                    new IOException(TAG + " blocked")
                );
            }
        } catch (Throwable ignored) {
        }

        param.setResult(null);
    }

    private static String getUrlFromRequest(
        Object request
    ) {
        if (request == null) {
            return null;
        }

        try {
            String text = request.toString();

            if (text == null) {
                return null;
            }

            int start = text.indexOf("url=");

            if (start < 0) {
                return null;
            }

            int end = text.indexOf(
                ", ",
                start
            );

            if (end < 0) {
                end = text.length();
            }

            String url =
                text.substring(
                    start + 4,
                    end
                );

            return url.startsWith("http")
                ? url
                : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getUrlFromRealCall(
        Object call
    ) {
        if (call == null) {
            return null;
        }

        try {
            Object request =
                XposedHelpers.callMethod(
                    call,
                    "request"
                );

            String url =
                getUrlFromRequest(request);

            if (url != null) {
                return url;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object request =
                XposedHelpers.getObjectField(
                    call,
                    "originalRequest"
                );

            return getUrlFromRequest(request);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
