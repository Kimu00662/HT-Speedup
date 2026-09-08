package com.htspeedup;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

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
        "free_recommend_status", "is_refresh_netease_token", "quality_copywriting_group",
        "movie/risk/list", "notification-settings", "ali_log_token", "report_logic",
        "online_status", "focus_info", "chat_plan", "emoji_rain", "vip_gift",
        "send_gift", "closed_friend", "bubble_tips", "voice_input", "translate_config",
        "get_pay_chat_info", "livehub/user/status", "get_latest_chat_plans",
        "chat_list_banner", "vip_trial/banner", "login_config/business/vip_product",
        "chat_magic_wand", "click_word_magic_wand", "publishing_skills",
        "vip_page_banner", "vip_page_content", "vip_info", "vip_page", "vip_banner",
        "vip_privilege_config", "vip_status_report", "content_paywall",
        "nobility_birthday", "product_list", "virtual_pay", "learn_tab",
        "system_notice", "mnt_info", "recommender_follow", "moment_tab_info",
        "list_op_uid", "query_expose_record", "post_recommend_btn", "like_popup",
        "newbie_task", "get_user_sealing", "ip_info", "guest_config", "launch_config",
        "platform/banner", "top_menu", "htserver/report", "sdkcs/verify", "wns_config",
        "livehub/live_voice", "live_voice/cfg", "multi_lang", "ht_advert",
        "rewarded_advert", "login_config/business/advert", "v_cube.license",
        "meme/user", "meme/batch_detail", "moment/notify", "exchange_list",
        "chat_assist", "translate/v2/config", "translate/v2/sts", "cards",
        "magic_wand/main", "get_course_module", "profile_banner", "learn_record",
        "user_virtual_info", "settle_center",
    };

    private static volatile long lastToUserChatTs = 0;
    private static final long TO_USER_CHAT_DEDUP_MS = 10_000;

    // 记住当前聊天的 userId
    private static final ThreadLocal<Integer> CHAT_USER_ID = new ThreadLocal<>();

    // userinfo 查询去重：userId -> 上次查询时间
    private static final ConcurrentHashMap<Integer, Long> userinfoQueryRecord = new ConcurrentHashMap<>();
    private static final long USERINFO_QUERY_INTERVAL_MS = 20_000;  // 20秒内不重复查同一用户

    private static final XC_MethodHook NOOP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.hellotalk".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + " ===== 模块开始加载 =====");

        hookApplication(lpp);
        hookUserInfoProviderLoad(lpp);      // 秒进的关键
        hookNetworkTimeoutToastSuppression(lpp);  // 屏蔽“网络超时”黑框
        hookLaunchSplash(lpp);              // ← 新增：隐藏启动大图标
        hookTitleController(lpp);
        hookChatDetailFragment(lpp);
        hookNewCall(lpp);
        hookRealCall(lpp);
        hookChatPage(lpp);

        XposedBridge.log(TAG + " ===== 钩子安装完成 =====");
    }

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpp.classLoader, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " Application.onCreate 激活");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook Application 失败: " + t.getMessage());
        }
    }

    /**
     * 屏蔽“网络超时，请重试 / 網絡超時，請重試 / ネットワークが途絶えました…”黑框。
     * 三条文案是同一资源 ID：0x7f141387（network_timed_out_retry）。
     * 统一出口：gm6.o(int resId, Context)。
     */
    private void hookNetworkTimeoutToastSuppression(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> gm6Class = XposedHelpers.findClass("gm6", lpp.classLoader);
            XposedBridge.hookAllMethods(gm6Class, "o", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object resIdObj = param.args[0];
                        if (!(resIdObj instanceof Integer)) return;
                        int resId = (Integer) resIdObj;
                        if (resId == 0x7f141387) {
                            param.setResult(null);
                            XposedBridge.log(TAG + " 已屏蔽网络超时黑框 (0x7f141387)");
                        }
                    } catch (Throwable t) {
                        // ignored
                    }
                }
            });
            XposedBridge.log(TAG + " hook gm6.o 网络超时黑框拦截成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook gm6.o 失败: " + t.getMessage());
        }
    }

    /**
     * 隐藏启动页 HelloTalk 大图标。
     * LaunchActivity.o0() = inflateLaunchContent，填充 logo 布局后把 logo View 存到：
     *   B = FrameLayout（logo 容器）
     *   C = ImageView（大图标）
     * 在这里把它们设为 GONE，启动页就不显示大图标，后续主流程不受影响。
     */
    private void hookLaunchSplash(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> launchActivity = XposedHelpers.findClass(
                "com.hellotalk.lib.main.launch.ui.LaunchActivity", lpp.classLoader);

            XposedBridge.hookAllMethods(launchActivity, "o0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object activity = param.thisObject;

                        Object logoContainer = XposedHelpers.getObjectField(activity, "B");
                        if (logoContainer instanceof android.view.View) {
                            ((android.view.View) logoContainer).setVisibility(android.view.View.GONE);
                        }

                        Object logoImage = XposedHelpers.getObjectField(activity, "C");
                        if (logoImage instanceof android.view.View) {
                            ((android.view.View) logoImage).setVisibility(android.view.View.GONE);
                        }

                        XposedBridge.log(TAG + " 已隐藏启动大图标");
                    } catch (Throwable t) {
                        // ignored
                    }
                }
            });

            XposedBridge.log(TAG + " hook LaunchActivity.o0 启动大图标隐藏成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook LaunchActivity.o0 失败: " + t.getMessage());
        }
    }

    // ★ 秒进的核心：hook Lyrv.b()，判断本地缓存充分就直接返回，不走协程查询
    private void hookUserInfoProviderLoad(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> yrvClass = XposedHelpers.findClass("yrv", lpp.classLoader);

            // hook b(int userId, List fields, int mode, orv callback, r06 continuation)
            XposedBridge.hookAllMethods(yrvClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 参数: args[0]=userId, args[1]=fields, args[2]=mode, args[3]=callback, args[4]=continuation
                        if (param.args == null || param.args.length < 5) return;

                        Object userIdObj = param.args[0];
                        if (!(userIdObj instanceof Integer)) return;
                        int userId = (Integer) userIdObj;

                        // 检查本地缓存是否充分
                        if (hasEnoughCache(yrvClass, userId)) {
                            // 本地缓存充分，直接返回缓存对象，不启动协程
                            Object cachedData = getCachedUserInfo(yrvClass, userId);
                            if (cachedData != null) {
                                param.setResult(cachedData);
                                XposedBridge.log(TAG + " userinfo 本地缓存秒回: userId=" + userId);
                                return;
                            }
                        }

                        // 缓存不足，让原方法走协程查询，但限制去重
                        long now = System.currentTimeMillis();
                        Long lastQuery = userinfoQueryRecord.get(userId);
                        if (lastQuery != null && now - lastQuery < USERINFO_QUERY_INTERVAL_MS) {
                            // 20秒内已查过，跳过网络查询，用缓存返回
                            Object cachedData = getCachedUserInfo(yrvClass, userId);
                            if (cachedData != null) {
                                param.setResult(cachedData);
                                XposedBridge.log(TAG + " userinfo 去重: userId=" + userId);
                                return;
                            }
                        }

                        userinfoQueryRecord.put(userId, now);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " userinfo provider hook error: " + t.getMessage());
                    }
                }
            });

            XposedBridge.log(TAG + " hook UserInfoProvider.b() 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook UserInfoProvider 失败: " + t.getMessage());
        }
    }

    // 检查本地缓存是否充分（有 UserBaseInfo 和 UserOnline）
    private static boolean hasEnoughCache(Class<?> yrvClass, int userId) {
        try {
            // 获取 Lyrv 单例
            Object provider = XposedHelpers.getStaticObjectField(yrvClass, "a");
            if (provider == null) return false;

            // 读 Lyrv.d (内存缓存 w1i)
            Object cache = XposedHelpers.getObjectField(provider, "d");
            if (cache == null) return false;

            // 查缓存里有没有这个 userId 的数据
            Object cachedData = XposedHelpers.callMethod(cache, "c", (Integer) userId);
            if (cachedData == null) return false;

            // 检查 UserInfoModel 里是否有必要字段
            Object baseInfo = XposedHelpers.callMethod(cachedData, "d");  // UserBaseInfo
            Object userOnline = XposedHelpers.callMethod(cachedData, "u");  // UserOnline

            // 都有就认为缓存充分
            return baseInfo != null && userOnline != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // 从本地缓存取 UserInfoModel
    private static Object getCachedUserInfo(Class<?> yrvClass, int userId) {
        try {
            Object provider = XposedHelpers.getStaticObjectField(yrvClass, "a");
            if (provider == null) return null;

            Object cache = XposedHelpers.getObjectField(provider, "d");
            if (cache == null) return null;

            return XposedHelpers.callMethod(cache, "c", (Integer) userId);
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookTitleController(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> pitClass = XposedHelpers.findClass("pit", lpp.classLoader);

            XposedBridge.hookAllMethods(pitClass, "M", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object titleController = param.thisObject;
                        Object userOnline = XposedHelpers.getObjectField(titleController, "i");
                        Object baseInfo = XposedHelpers.getObjectField(titleController, "k");

                        if (userOnline != null && baseInfo != null) {
                            param.setResult(null);
                            XposedBridge.log(TAG + " 标题栏缓存充分，跳过查询");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Lpit.M() error: " + t.getMessage());
                    }
                }
            });

            XposedBridge.log(TAG + " hook Lpit.M() 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook title failed: " + t.getMessage());
        }
    }

    private void hookChatDetailFragment(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> fragClass = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.fragment.ChatDetailFragment", lpp.classLoader);

            XposedBridge.hookAllMethods(fragClass, "setArguments", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        android.os.Bundle bundle = (android.os.Bundle) param.args[0];
                        if (bundle != null) {
                            int userId = bundle.getInt("user_id", 0);
                            if (userId > 0) {
                                CHAT_USER_ID.set(userId);
                                XposedBridge.log(TAG + " ChatFragment userId=" + userId);
                            }
                        }
                    } catch (Throwable t) {
                        // ignored
                    }
                }
            });

            XposedBridge.log(TAG + " hook ChatDetailFragment 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook ChatDetailFragment 失败: " + t.getMessage());
        }
    }

    private void hookNewCall(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> clientClass = XposedHelpers.findClass("okhttp3.OkHttpClient", lpp.classLoader);
            XposedBridge.hookAllMethods(clientClass, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        Object request = param.args[0];
                        String u = getUrlFromRequest(request);
                        if (u == null) return;
                        if (shouldBlockUrl(u)) {
                            param.setThrowable(new IOException(TAG + " blocked"));
                        }
                    } catch (Throwable t) {
                        // ignored
                    }
                }
            });
            XposedBridge.log(TAG + " hook newCall 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook newCall 失败: " + t.getMessage());
        }
    }

    private static boolean shouldBlockUrl(String u) {
        if (u.contains("ht_im/sock")) return false;

        if (u.contains("p2p-chat/to-user-chat")) {
            long now = System.currentTimeMillis();
            if (now - lastToUserChatTs < TO_USER_CHAT_DEDUP_MS) return true;
            lastToUserChatTs = now;
            return false;
        }

        for (String p : BLOCK_PATHS) {
            if (u.contains(p)) return true;
        }
        return false;
    }

    private void hookRealCall(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> realCall = null;
        String[] candidates = {"okhttp3.internal.connection.RealCall", "okhttp3.RealCall"};
        for (String name : candidates) {
            try {
                realCall = XposedHelpers.findClass(name, lpp.classLoader);
                break;
            } catch (Throwable ignored) {}
        }
        if (realCall == null) return;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String u = getUrlFromRealCall(param.thisObject);
                    if (u == null) return;
                    if (shouldBlockUrl(u)) {
                        blockRequest(param);
                    }
                } catch (Throwable t) {
                    // ignored
                }
            }
        };

        try {
            XposedBridge.hookAllMethods(realCall, "enqueue", hook);
            XposedBridge.log(TAG + " hook RealCall.enqueue 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook enqueue 失败: " + t.getMessage());
        }
    }

    private void hookChatPage(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> frag = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.fragment.ChatDetailFragment", lpp.classLoader);
            hookVoidMethod(frag, "T3");
            hookVoidMethod(frag, "R3");
            try { XposedHelpers.findAndHookMethod(frag, "S3", boolean.class, NOOP_HOOK); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(frag, "F3", boolean.class, NOOP_HOOK); } catch (Throwable ignored) {}
            XposedBridge.log(TAG + " hook ChatDetailFragment 成功");
        } catch (Throwable t) {
            // ignored
        }

        try {
            Class<?> vm = XposedHelpers.findClass("ha4", lpp.classLoader);
            try {
                Class<?> tc2 = XposedHelpers.findClass("tc2", lpp.classLoader);
                XposedHelpers.findAndHookMethod(vm, "R", tc2, NOOP_HOOK);
            } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(vm, "P", int.class, int.class, NOOP_HOOK); } catch (Throwable ignored) {}
            try { XposedHelpers.findAndHookMethod(vm, "A", java.util.List.class, NOOP_HOOK); } catch (Throwable ignored) {}
            XposedBridge.log(TAG + " hook ChatDetailViewModel 成功");
        } catch (Throwable t) {
            // ignored
        }
    }

    private void hookVoidMethod(Class<?> clazz, String name) {
        try { XposedHelpers.findAndHookMethod(clazz, name, NOOP_HOOK); } catch (Throwable ignored) {}
    }

    private static void blockRequest(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args != null && param.args.length > 0) {
                XposedHelpers.callMethod(param.args[0], "onFailure", param.thisObject,
                    new IOException(TAG + " blocked"));
            }
        } catch (Throwable ignored) {}
        param.setResult(null);
    }

    private static String getUrlFromRequest(Object request) {
        if (request == null) return null;
        try {
            String s = request.toString();
            if (s != null) {
                int u = s.indexOf("url=");
                if (u >= 0) {
                    int end = s.indexOf(", ", u);
                    if (end < 0) end = s.length();
                    String url = s.substring(u + 4, end);
                    if (url.startsWith("http")) return url;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getUrlFromRealCall(Object call) {
        if (call == null) return null;
        try {
            Object req = XposedHelpers.callMethod(call, "request");
            return getUrlFromRequest(req);
        } catch (Throwable ignored) {}
        try {
            Object req = XposedHelpers.getObjectField(call, "originalRequest");
            return getUrlFromRequest(req);
        } catch (Throwable ignored) {}
        return null;
    }
}
