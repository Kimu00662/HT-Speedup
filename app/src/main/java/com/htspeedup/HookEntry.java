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
        "get_course_module",
        "profile_banner",
        "learn_record",
        "user_virtual_info",
        "settle_center",
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

        XposedBridge.log(TAG + " ===== 模块开始加载 =====");

        hookApplication(lpp);
        hookNewCall(lpp);
        hookRealCall(lpp);
        hookChatPage(lpp);

        XposedBridge.log(TAG + " ===== 钩子安装完成 =====");
    }

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", lpp.classLoader, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " Application.onCreate 触发，模块已激活");
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook Application 失败: " + t.getMessage());
        }
    }

    private void hookNewCall(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> clientClass = XposedHelpers.findClass("okhttp3.OkHttpClient", lpp.classLoader);
            int n = XposedBridge.hookAllMethods(clientClass, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        Object request = param.args[0];
                        String u = getUrlFromRequest(request);
                        if (u == null) return;
                        if (shouldBlockUrl(u)) {
                            param.setThrowable(new IOException(TAG + " blocked via newCall"));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " newCall hook 异常: " + t.getMessage());
                    }
                }
            }).size();
            XposedBridge.log(TAG + " hook OkHttpClient.newCall(hookAll) 命中方法数=" + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook newCall 失败: " + t.getMessage());
        }
    }

    /** 返回 true 表示应拦截。集中处理白名单/去重/黑名单。 */
    private static boolean shouldBlockUrl(String u) {
        if (u.contains("ht_im/sock")) return false;

        if (u.contains("p2p-chat/to-user-chat")) {
            long now = System.currentTimeMillis();
            if (now - lastToUserChatTs < TO_USER_CHAT_DEDUP_MS) return true;
            lastToUserChatTs = now;
            return false;
        }

        if (u.contains("profile/v2/userinfo")) {
            long now = System.currentTimeMillis();
            if (now - lastUserinfoTs < USERINFO_DEDUP_MS) return true;
            lastUserinfoTs = now;
            return false;
        }

        for (String p : BLOCK_PATHS) {
            if (u.contains(p)) return true;
        }
        return false;
    }

    private void hookRealCall(XC_LoadPackage.LoadPackageParam lpp) {
        Class<?> realCall = null;
        String[] candidates = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.RealCall",
        };
        for (String name : candidates) {
            try {
                realCall = XposedHelpers.findClass(name, lpp.classLoader);
                XposedBridge.log(TAG + " 找到 RealCall: " + name);
                break;
            } catch (Throwable ignored) {}
        }
        if (realCall == null) {
            XposedBridge.log(TAG + " 未找到 RealCall，跳过");
            return;
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String u = getUrlFromRealCall(param.thisObject);
                    if (u == null) {
                        logRealCallDiagnosticsOnce(param.thisObject);
                        return;
                    }
                    boolean block = shouldBlockUrl(u);
                    logUrlOnce(u, block, param.method.getName());
                    if (block) {
                        blockRequest(param);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " RealCall hook 异常: " + t.getMessage());
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(realCall, "execute", hook);
            XposedBridge.log(TAG + " hook RealCall.execute 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook execute 失败: " + t.getMessage());
        }

        try {
            Class<?> cb = XposedHelpers.findClass("okhttp3.Callback", lpp.classLoader);
            XposedHelpers.findAndHookMethod(realCall, "enqueue", cb, hook);
            XposedBridge.log(TAG + " hook RealCall.enqueue 成功");
        } catch (Throwable t) {
            try {
                XposedBridge.hookAllMethods(realCall, "enqueue", hook);
                XposedBridge.log(TAG + " hook enqueue (hookAll) 成功");
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " hook enqueue 失败: " + t2.getMessage());
            }
        }
    }

    private static String getUrlFromRequest(Object request) {
        if (request == null) return null;
        // OkHttp 4.x Kotlin: url() 方法返回 HttpUrl，toString() 含完整路径
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            if (url != null) {
                String s = url.toString();
                if (s != null && s.length() > 0) return s;
            }
        } catch (Throwable ignored) {}
        // getUrl()
        try {
            Object url = XposedHelpers.callMethod(request, "getUrl");
            if (url != null) return url.toString();
        } catch (Throwable ignored) {}
        // 直接读字段
        try {
            Object url = XposedHelpers.getObjectField(request, "url");
            if (url != null) return url.toString();
        } catch (Throwable ignored) {}
        // 遍历字段找 HttpUrl 类型
        try {
            for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().contains("HttpUrl")) {
                        f.setAccessible(true);
                        Object url = f.get(request);
                        if (url != null) return url.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getUrlFromRealCall(Object call) {
        if (call == null) return null;
        // request() 方法拿 Request 对象，再取完整 URL（含路径）
        try {
            Object req = XposedHelpers.callMethod(call, "request");
            String u = getUrlFromRequest(req);
            if (u != null) return u;
        } catch (Throwable ignored) {}
        // getOriginalRequest()
        try {
            Object req = XposedHelpers.callMethod(call, "getOriginalRequest");
            String u = getUrlFromRequest(req);
            if (u != null) return u;
        } catch (Throwable ignored) {}
        // originalRequest 字段
        try {
            Object req = XposedHelpers.getObjectField(call, "originalRequest");
            String u = getUrlFromRequest(req);
            if (u != null) return u;
        } catch (Throwable ignored) {}
        return null;
    }

    private static volatile boolean diagLogged = false;
    private static volatile int urlLogCount = 0;
    private static final int URL_LOG_LIMIT = 30;

    private static void logUrlOnce(String url, boolean block, String method) {
        if (urlLogCount >= URL_LOG_LIMIT) return;
        urlLogCount++;
        String shortUrl = url.length() > 80 ? url.substring(0, 80) + "..." : url;
        XposedBridge.log(TAG + " [" + method + "] block=" + block + " url=" + shortUrl);
    }

    private static void logRealCallDiagnosticsOnce(Object call) {
        if (diagLogged || call == null) return;
        diagLogged = true;
        try {
            StringBuilder sb = new StringBuilder(TAG + " 诊断 RealCall 类=" + call.getClass().getName());
            sb.append("\n  方法:");
            for (java.lang.reflect.Method m : call.getClass().getMethods()) {
                String n = m.getName();
                if (n.contains("equest") || n.equals("url") || n.contains("Url")) {
                    sb.append(' ').append(n).append('(').append(m.getParameterTypes().length).append(")=").append(m.getReturnType().getSimpleName());
                }
            }
            sb.append("\n  字段:");
            for (Class<?> c = call.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    sb.append(' ').append(f.getName()).append(':').append(f.getType().getSimpleName());
                }
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 诊断失败: " + t.getMessage());
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
            XposedBridge.log(TAG + " hook ChatDetailFragment 失败: " + t.getMessage());
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
            XposedBridge.log(TAG + " hook ChatDetailViewModel 失败: " + t.getMessage());
        }
    }

    private void hookVoidMethod(Class<?> clazz, String name) {
        try { XposedHelpers.findAndHookMethod(clazz, name, NOOP_HOOK); } catch (Throwable ignored) {}
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
