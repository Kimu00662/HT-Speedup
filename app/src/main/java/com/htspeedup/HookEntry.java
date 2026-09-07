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

    // userinfo 按 body 指纹去重（区分自己/对方），to-user-chat 按完整 URL 去重
    private static final ConcurrentHashMap<String, Long> userinfoDedup = new ConcurrentHashMap<>();
    private static final long USERINFO_DEDUP_MS = 15_000;

    private static final ConcurrentHashMap<String, Long> toUserChatDedup = new ConcurrentHashMap<>();
    private static final long TO_USER_CHAT_DEDUP_MS = 15_000;

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
        // UserInfoProvider（混淆名 yrv）是 userinfo 数据的唯一入口，有 loadRam/loadCache/loadNet 三级。
        // 新版把「在线状态」合并进了 userinfo，但真相是：在线状态由 IM 推送实时写库
        // （IMUserServiceImpl.j1 -> UserInfoDao.updateUserOnlineInfo），标题栏读本地 DB 即可。
        // 修复：hook load 入口（b 方法），把加载模式（mode）强制改为 1=走缓存，
        // 这样标题栏 queryUserInfo 走本地缓存（秒显不卡），在线状态靠 IM 推送保持实时（不失真）。
        try {
            Class<?> providerClass = XposedHelpers.findClass("yrv", lpp.classLoader);
            XposedBridge.hookAllMethods(providerClass, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // b(ILjava/util/List;ILorv;Lp06;) —— args[0]=userId, args[2]=mode(0=网络,1=缓存)
                        if (param.args == null || param.args.length < 3) return;
                        Object modeObj = param.args[2];
                        if (!(modeObj instanceof Integer)) return;
                        int mode = (Integer) modeObj;
                        if (mode == 0) {
                            param.args[2] = 1; // 强制走缓存
                            XposedBridge.log(TAG + " userinfo 走缓存 mode: 0->1");
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

    /** 返回 true 表示应拦截。u=URL，request=Request对象（用于 body 指纹）。 */
    private static boolean shouldBlock(String u, Object request) {
        // IM 长连接，放行
        if (u.contains("ht_im/sock")) return false;

        // to-user-chat 按完整 URL 去重（URL 含 user_id，自然区分不同会话）
        if (u.contains("p2p-chat/to-user-chat")) {
            long now = System.currentTimeMillis();
            Long last = toUserChatDedup.get(u);
            if (last != null && now - last < TO_USER_CHAT_DEDUP_MS) return true;
            toUserChatDedup.put(u, now);
            return false;
        }

        // userinfo 按 body 指纹去重：自己/对方各自放行首次，后续重复才拦截。
        // 在线状态由 IM 推送实时写库（标题栏走 mode=1 缓存读本地 DB），
        // 所以 HTTP 层去重不会导致在线状态失真，反而带来秒进。
        if (u.contains("profile/v2/userinfo")) {
            String fp = getBodyFingerprint(request);
            String key = (fp != null) ? fp : u;
            long now = System.currentTimeMillis();
            Long last = userinfoDedup.get(key);
            if (last != null && now - last < USERINFO_DEDUP_MS) return true;
            userinfoDedup.put(key, now);
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
                    // execute 是同步请求，拦截会抛异常导致 app 弹网络错误，直接放行
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

    private static String getUrlFromRealCall(Object call) {
        return getUrlFromRequest(getRequestFromRealCall(call));
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

    /** 从 Request 对象提取 body 字节指纹（前32字节hex+长度），用于 userinfo 按内容区分去重。 */
    private static String getBodyFingerprint(Object request) {
        if (request == null) return null;
        try {
            for (Class<?> c = request.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(request); } catch (Throwable ignored) { continue; }
                    if (v == null) continue;
                    String hex = extractBytesFingerprint(v);
                    if (hex != null) return hex;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractBytesFingerprint(Object obj) {
        if (obj == null) return null;
        if (obj instanceof byte[]) {
            return bytesToFingerprint((byte[]) obj);
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == byte[].class) {
                        f.setAccessible(true);
                        byte[] b = (byte[]) f.get(obj);
                        if (b != null && b.length > 0) return bytesToFingerprint(b);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String bytesToFingerprint(byte[] b) {
        int n = Math.min(32, b.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.append('#').append(b.length).toString();
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
        // 只处理 enqueue（execute 已在 hook 里提前放行）
        // 静默丢弃：不回调 onFailure，app 收不到失败通知，不会弹网络错误提示
        param.setResult(null);
    }
}
