package com.htspeedup;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "HT_Diag";

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

        XposedBridge.log(TAG + " ===== 诊断模块开始加载 =====");

        hookDiagnostics(lpp);
        hookUserInfoProviderLoad(lpp);
        hookNetworkTimeoutToastSuppression(lpp);
        hookTitleController(lpp);
        hookChatDetailFragment(lpp);
        hookNewCall(lpp);
        hookRealCall(lpp);
        hookChatPage(lpp);

        XposedBridge.log(TAG + " ===== 诊断钩子安装完成 =====");
    }

    /**
     * 诊断核心。
     *
     * 目标：测量后台切回时，到底卡在哪个阶段。
     */
    private void hookDiagnostics(
        XC_LoadPackage.LoadPackageParam lpp
    ) {
        try {
            final Class<?> mainActivity = XposedHelpers.findClass(
                "com.hellotalk.lib.main.home.ui.MainTabV3Activity",
                lpp.classLoader
            );

            final Class<?> launchActivity = XposedHelpers.findClass(
                "com.hellotalk.lib.main.launch.ui.LaunchActivity",
                lpp.classLoader
            );

            /*
             * MainTabV3Activity 生命周期时间戳。
             */
            hookTime(mainActivity, "onCreate",
                new String[]{"android.os.Bundle"});
            hookTime(mainActivity, "onStart", null);
            hookTime(mainActivity, "onResume", null);
            hookTime(mainActivity, "onPause", null);
            hookTime(mainActivity, "onStop", null);
            hookTime(mainActivity, "onWindowFocusChanged",
                new String[]{"boolean"});

            /*
             * LaunchActivity 生命周期时间戳。
             *
             * 用于确认切回时 LaunchActivity 是否被重新创建。
             */
            hookTime(launchActivity, "onCreate",
                new String[]{"android.os.Bundle"});
            hookTime(launchActivity, "onResume", null);
            hookTime(launchActivity, "onPause", null);
            hookTime(launchActivity, "onWindowFocusChanged",
                new String[]{"boolean"});

            XposedBridge.log(TAG + " 生命周期诊断钩子安装成功");
        } catch (Throwable t) {
            XposedBridge.log(
                TAG + " 生命周期诊断钩子安装失败: "
                    + t.getMessage()
            );
        }
    }

    private void hookTime(
        final Class<?> clazz,
        final String methodName,
        final String[] paramTypes
    ) {
        try {
            Object[] paramsAndHook = null;

            if (paramTypes == null) {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 开始"
                            );
                        }

                        @Override
                        protected void afterHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 结束"
                            );
                        }
                    }
                );
            } else if ("boolean".equals(paramTypes[0])) {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 开始 hasFocus="
                                    + param.args[0]
                            );
                        }

                        @Override
                        protected void afterHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 结束 hasFocus="
                                    + param.args[0]
                            );
                        }
                    }
                );
            } else {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    methodName,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 开始"
                            );
                        }

                        @Override
                        protected void afterHookedMethod(
                            MethodHookParam param
                        ) {
                            logTime(
                                clazz.getSimpleName()
                                    + "." + methodName
                                    + " 结束"
                            );
                        }
                    }
                );
            }
        } catch (Throwable t) {
            XposedBridge.log(
                TAG
                    + " 无法 hook "
                    + clazz.getSimpleName()
                    + "."
                    + methodName
                    + ": "
                    + t.getMessage()
            );
        }
    }

    private static void logTime(String message) {
        XposedBridge.log(
            TAG
                + " ["
                + System.currentTimeMillis()
                + "] "
                + message
        );
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
                TAG + " hook gm6.o 成功"
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
        } catch (Throwable t) {
            XposedBridge.log(
                TAG + " hook UserInfoProvider 失败: "
                    + t.getMessage()
            );
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
