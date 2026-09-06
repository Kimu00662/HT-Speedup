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
    };

    private static volatile long lastUserinfoTs = 0;
    private static final long USERINFO_DEDUP_MS = 30_000;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"com.hellotalk".equals(lpp.packageName)) return;

        XposedBridge.log(TAG + " 模块已加载");

        Class<?> realCall = null;
        try {
            realCall = XposedHelpers.findClass("okhttp3.internal.connection.RealCall", lpp.classLoader);
        } catch (Throwable t) {
            try {
                realCall = XposedHelpers.findClass("okhttp3.RealCall", lpp.classLoader);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " 未找到 OkHttp RealCall，放弃");
                return;
            }
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object request = XposedHelpers.callMethod(param.thisObject, "request");
                    Object url = XposedHelpers.callMethod(request, "url");
                    String u = url.toString();

                    if (u.contains("ht_im") || u.contains("p2p-chat")) return;

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
                } catch (Throwable ignored) {}
            }
        };

        try {
            XposedHelpers.findAndHookMethod(realCall, "execute", hook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook execute 失败: " + t.getMessage());
        }

        try {
            Class<?> callbackClass = XposedHelpers.findClass("okhttp3.Callback", lpp.classLoader);
            XposedHelpers.findAndHookMethod(realCall, "enqueue", callbackClass, hook);
        } catch (Throwable t) {
            try {
                XposedBridge.hookAllMethods(realCall, "enqueue", hook);
            } catch (Throwable t2) {
                XposedBridge.log(TAG + " hook enqueue 失败: " + t2.getMessage());
            }
        }

        XposedBridge.log(TAG + " 钩子安装完成");
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
