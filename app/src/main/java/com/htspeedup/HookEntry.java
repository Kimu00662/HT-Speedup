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

    private static final XC_MethodHook NOOP_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(null);
        }
    };

    // UserInfoProvider 走缓存：userId -> 上次网络加载时间（同 userId N 秒内强制走缓存）
    private static final ConcurrentHashMap<Integer, Long> userinfoLoadTs = new ConcurrentHashMap<>();
    private static final long USERINFO_CACHE_WINDOW_MS = 10_000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.hellotalk".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + " ===== 模块开始加载 =====");

        hookApplication(lpp);
        hookUserInfoProvider(lpp);
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

    private void hookUserInfoProvider(XC_LoadPackage.LoadPackageParam lpp) {
        // UserInfoProvider（混淆名 yrv）是 userinfo 数据的唯一入口，有 loadRam/loadCache/loadNet 三级缓存
        // 卡顿根源：app 联网时每次进聊天页都走 loadNet（网络），而断网时走 loadCache（秒进）
        // 修复：hook load 入口（b 方法），同 userId 10 秒内把网络加载（mode=0）改为缓存加载（mode=1）
        try {
            Class<?> providerClass = XposedHelpers.findClass("yrv", lpp.classLoader);
            XposedBridge.hookAllMethods(providerClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        Object uidObj = param.args[0];
                        Object modeObj = param.args[2];
                        if (!(uidObj instanceof Integer) || !(modeObj instanceof Integer)) return;
                        int uid = (Integer) uidObj;
                        int mode = (Integer) modeObj;
                        if (mode != 0) return; // 已是缓存模式，不动
                        long now = System.currentTimeMillis();
                        Long last = userinfoLoadTs.get(uid);
                        if (last != null && now - last < USERINFO_CACHE_WINDOW_MS) {
                            param.args[2] = 1; // 窗口内强制走缓存
                            XposedBridge.log(TAG + " userinfo 走缓存 uid=" + uid);
                        } else {
                            userinfoLoadTs.put(uid, now);
                            XposedBridge.log(TAG + " userinfo 走网络 uid=" + uid);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " userinfo provider hook 异常: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " hook UserInfoProvider.load 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook UserInfoProvider 失败: " + t.getMessage());
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
                        if (shouldBlock(u, request)) {
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

    /** 返回 true 表示应拦截。u=URL，request=Request对象。 */
    private static boolean shouldBlock(String u, Object request) {
        // 以下都是聊天页/个人页展示必需的数据，放行
        if (u.contains("ht_im/sock")) return false;
        if (u.contains("p2p-chat/to-user-chat")) return false;
        if (u.contains("profile/v2/userinfo")) return false;
        if (u.contains("profile/v1/get_pay_chat_info")) return false;
        if (u.contains("livehub/user/status")) return false;

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
                    // execute 是同步请求，放行（拦截会抛异常弹网络错误）
                    if ("execute".equals(param.method.getName())) {
                        return;
                    }
                    Object request = getRequestFromRealCall(param.thisObject);
                    if (request == null) {
                        logRealCallDiagnosticsOnce(param.thisObject);
                        return;
                    }
                    String u = getUrlFromRequest(request);
                    if (u == null) return;
                    boolean block = shouldBlock(u, request);
                    logUrlOnce(u, block, param.method.getName());
                    if (block) {
                        blockRequest(param, u);
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
        // 最简单可靠：Request.toString() 打印 Request{method=GET, url=..., headers=[...]}
        // toString 是 Object 方法，R8 不会混淆，url= 后面的就是完整 URL
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
        // OkHttp 4.x Kotlin: url() 方法（可能被混淆成 a()）
        String[] urlMethods = {"url", "getUrl", "a"};
        for (String m : urlMethods) {
            try {
                Object url = XposedHelpers.callMethod(request, m);
                if (url != null) {
                    String s = url.toString();
                    if (s != null && s.startsWith("http")) return s;
                }
            } catch (Throwable ignored) {}
        }
        // 直接读字段（可能被混淆成 a）
        String[] urlFields = {"url", "a"};
        for (String f : urlFields) {
            try {
                Object url = XposedHelpers.getObjectField(request, f);
                if (url != null) {
                    String s = url.toString();
                    if (s != null && s.startsWith("http")) return s;
                }
            } catch (Throwable ignored) {}
        }
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

    private static Object getRequestFromRealCall(Object call) {
        if (call == null) return null;
        try {
            Object req = XposedHelpers.callMethod(call, "request");
            if (req != null) return req;
        } catch (Throwable ignored) {}
        try {
            Object req = XposedHelpers.callMethod(call, "getOriginalRequest");
            if (req != null) return req;
        } catch (Throwable ignored) {}
        try {
            Object req = XposedHelpers.getObjectField(call, "originalRequest");
            if (req != null) return req;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String getUrlFromRealCall(Object call) {
        return getUrlFromRequest(getRequestFromRealCall(call));
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

    private static void blockRequest(XC_MethodHook.MethodHookParam param, String url) {
        // 只处理 enqueue（execute 已在 hook 里提前放行）
        // 纯冗余请求：静默丢弃，不回调 onFailure（否则 app 弹网络错误提示）
        param.setResult(null);
    }
}
