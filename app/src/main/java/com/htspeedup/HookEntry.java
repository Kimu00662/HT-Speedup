package com.htspeedup;

import java.io.IOException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "HT_Speedup";

    private static final String[] BLOCK_PATHS = {
        "shortflix_api",
        "livehub/channel_list",
        "get_thirtysix_question",
        "language_partner/interested",
        "gift_shop",
        "resource_pre_load",
        "free_recommend_status",
        "is_refresh_netease_token",
        "quality_copywriting_group",
        "movie/risk/list",
        "notification-settings",
        "ali_log_token",
        "report_logic",
        "online_status",
        "focus_info",
        "chat_plan",
        "emoji_rain",
        "vip_gift",
        "send_gift",
        "closed_friend",
        "bubble_tips",
        "voice_input",
        "translate_config",
        "get_pay_chat_info",
        "livehub/user/status",
        "get_latest_chat_plans",
        "chat_list_banner",
        "vip_trial/banner",
        "login_config/business/vip_product",
        "chat_magic_wand",
        "click_word_magic_wand",
        "publishing_skills",
        "vip_page_banner",
        "vip_page_content",
        "vip_info",
        "vip_page",
        "vip_banner",
        "vip_privilege_config",
        "vip_status_report",
        "content_paywall",
        "nobility_birthday",
        "product_list",
        "virtual_pay",
        "learn_tab",
        "system_notice",
        "mnt_info",
        "recommender_follow",
        "moment_tab_info",
        "list_op_uid",
        "query_expose_record",
        "post_recommend_btn",
        "like_popup",
        "newbie_task",
        "get_user_sealing",
        "ip_info",
        "guest_config",
        "launch_config",
        "platform/banner",
        "top_menu",
        "htserver/report",
        "sdkcs/verify",
        "wns_config",
        "livehub/live_voice",
        "live_voice/cfg",
        "multi_lang",
        "ht_advert",
        "rewarded_advert",
        "login_config/business/advert",
        "v_cube.license",
        "meme/user",
        "meme/batch_detail",
        "moment/notify",
        "exchange_list",
        "chat_assist",
        "translate/v2/config",
        "translate/v2/sts",
        "cards",
        "magic_wand/main",
    };

    private static volatile long lastUserinfoTs = 0;
    private static final long USERINFO_DEDUP_MS = 10_000;

    private static volatile long lastToUserChatTs = 0;
    private static final long TO_USER_CHAT_DEDUP_MS = 10_000;

    private static final XC_MethodHook NOOP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.hellotalk".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + " 模块已加载，开始安装钩子");

        hookOkHttp(lpp);
        hookChatPage(lpp);

        XposedBridge.log(TAG + " 钩子安装完成");
    }

    private void hookOkHttp(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> realCall = null;
        String realCallName = null;

        String[] candidates = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall",
        };

        for (String name : candidates) {
            try {
                realCall = XposedHelpers.findClass(name, lpp.classLoader);
                realCallName = name;
                XposedBridge.log(TAG + " 找到 RealCall: " + name);
                break;
            } catch (Throwable ignored) {}
        }

        if (realCall == null) {
            XposedBridge.log(TAG + " 未找到任何 OkHttp RealCall，尝试 hook OkHttpClient.newCall");
            hookNewCall(lpp);
            return;
        }

        final Class<?> rc = realCall;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String u = extractUrl(param.thisObject);
                    if (u == null) return;

                    if (u.contains("ht_im/sock")) return;

                    if (u.contains("p2p-chat/to-user-chat")) {
                        long now = System.currentTimeMillis();
                        if (now - lastToUserChatTs < TO_USER_CHAT_DEDUP_MS) {
                            blockRequest(param);
                            return;
                        }
                        lastToUserChatTs = now;
                        return;
                    }

                    if (u.contains("profile/v2/userinfo")) {
                        long now = System.currentTimeMillis();
                        if (now - lastUserinfoTs < USERINFO_DEDUP_MS) {
                            blockRequest(param);
                            return;
                        }
                        lastUserinfoTs = now;
                        return;
                    }

                    for (String p : BLOCK_PATHS) {
                        if (u.contains(p)) {
                            blockRequest(param);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hook 内部异常: " + t.getMessage());
                }
            }
        };

        boolean hookedAny = false;

        try {
            XposedHelpers.findAndHookMethod(rc, "execute", hook);
            hookedAny = true;
            XposedBridge.log(TAG + " hook execute 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook execute 失败: " + t.getMessage());
        }

        try {
            Class<?> callbackClass = XposedHelpers.findClass("okhttp3.Callback", lpp.classLoader);
            XposedHelpers.findAndHookMethod(rc, "enqueue", callbackClass, hook);
            hookedAny = true;
            XposedBridge.log(TAG + " hook enqueue 成功");
        } catch (Throwable t) {
            try {
                XposedBridge.hookAllMethods(rc, "enqueue", hook);
                hookedAny = true;
                XposedBridge.log(TAG + " hook enqueue (hookAll) 成功");
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " hook enqueue 失败: " + t2.getMessage());
            }
        }

        if (!hookedAny) {
            XposedBridge.log(TAG + " RealCall hook 全部失败，尝试 newCall 方案");
            hookNewCall(lpp);
        }
    }

    private void hookNewCall(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> clientClass = XposedHelpers.findClass("okhttp3.OkHttpClient", lpp.classLoader);
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpp.classLoader);

            XposedHelpers.findAndHookMethod(clientClass, "newCall", requestClass, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object request = param.args[0];
                        String u = extractUrlFromRequest(request);
                        if (u == null) return;

                        if (u.contains("ht_im/sock")) return;

                        if (u.contains("p2p-chat/to-user-chat")) {
                            long now = System.currentTimeMillis();
                            if (now - lastToUserChatTs < TO_USER_CHAT_DEDUP_MS) {
                                param.setThrowable(new IOException(TAG + " blocked"));
                                return;
                            }
                            lastToUserChatTs = now;
                            return;
                        }

                        if (u.contains("profile/v2/userinfo")) {
                            long now = System.currentTimeMillis();
                            if (now - lastUserinfoTs < USERINFO_DEDUP_MS) {
                                param.setThrowable(new IOException(TAG + " blocked"));
                                return;
                            }
                            lastUserinfoTs = now;
                            return;
                        }

                        for (String p : BLOCK_PATHS) {
                            if (u.contains(p)) {
                                param.setThrowable(new IOException(TAG + " blocked"));
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log(TAG + " hook newCall 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook newCall 失败: " + t.getMessage());
        }
    }

    private static String extractUrl(Object realCallObj) {
        // OkHttp 4.x Kotlin: request() 方法
        try {
            Object request = XposedHelpers.callMethod(realCallObj, "request");
            return extractUrlFromRequest(request);
        } catch (Throwable ignored) {}

        // OkHttp 4.x Kotlin: getRequest() 方法
        try {
            Object request = XposedHelpers.callMethod(realCallObj, "getRequest");
            return extractUrlFromRequest(request);
        } catch (Throwable ignored) {}

        // 直接访问字段 originalRequest
        try {
            Object request = XposedHelpers.getObjectField(realCallObj, "originalRequest");
            return extractUrlFromRequest(request);
        } catch (Throwable ignored) {}

        try {
            Object request = XposedHelpers.getObjectField(realCallObj, "request");
            return extractUrlFromRequest(request);
        } catch (Throwable ignored) {}

        return null;
    }

    private static String extractUrlFromRequest(Object request) {
        if (request == null) return null;

        try {
            Object url = XposedHelpers.callMethod(request, "url");
            if (url != null) return url.toString();
        } catch (Throwable ignored) {}

        try {
            Object url = XposedHelpers.callMethod(request, "getUrl");
            if (url != null) return url.toString();
        } catch (Throwable ignored) {}

        try {
            Object url = XposedHelpers.getObjectField(request, "url");
            if (url != null) return url.toString();
        } catch (Throwable ignored) {}

        return null;
    }

    private void hookChatPage(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(
                "com.hellotalk.talk.detail.fragment.ChatDetailFragment", lpp.classLoader);

            hookVoidMethod(fragmentClass, "T3");
            hookVoidMethod(fragmentClass, "R3");

            try {
                XposedHelpers.findAndHookMethod(fragmentClass, "S3", boolean.class, NOOP_HOOK);
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(fragmentClass, "F3", boolean.class, NOOP_HOOK);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook ChatDetailFragment 失败: " + t.getMessage());
        }

        try {
            Class<?> vmClass = XposedHelpers.findClass("ha4", lpp.classLoader);

            try {
                Class<?> tc2Class = XposedHelpers.findClass("tc2", lpp.classLoader);
                XposedHelpers.findAndHookMethod(vmClass, "R", tc2Class, NOOP_HOOK);
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(vmClass, "P", int.class, int.class, NOOP_HOOK);
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(vmClass, "A", java.util.List.class, NOOP_HOOK);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook ChatDetailViewModel 失败: " + t.getMessage());
        }
    }

    private void hookVoidMethod(Class<?> clazz, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, NOOP_HOOK);
        } catch (Throwable ignored) {}
    }

    private static void blockRequest(XC_MethodHook.MethodHookParam param) {
        IOException ex = new IOException(TAG + " blocked");
        if ("execute".equals(param.method.getName())) {
            param.setThrowable(ex);
        } else {
            try {
                XposedHelpers.callMethod(param.args[0], "onFailure", param.thisObject, ex);
            } catch (Throwable ignored) {}
            param.setResult(null);
        }
    }
}
