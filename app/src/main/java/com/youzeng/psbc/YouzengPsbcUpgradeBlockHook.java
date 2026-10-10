package com.youzeng.psbc;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 拦截邮储 App 启动时的自动版本检查与更新弹窗。
 * <p>
 * 升级链路（jadx）：
 * <ul>
 *   <li>UMP：{@code MainActivity.checkForAppUpgrade()} → {@code PublicViewModel.checkForUpgrade()}</li>
 *   <li>UMP 弹窗：{@code MainActivity.showDownloadDialog(UpgradeApi.Response)} → {@code DialogManager.showDialogUpdateApk}</li>
 *   <li>mPaaS：{@code MPaaSCheckVersionServiceImpl.checkNewVersion()} → {@code UpgradeMainCallBack.showUpgradeDialog()}</li>
 *   <li>mPaaS 弹窗：{@code MainActivity.showDownloadDialog(ClientUpgradeRes)}</li>
 *   <li>首页弹窗队列 popId=1002 也会延迟调用 {@code showDownloadDialog}</li>
 * </ul>
 */
public final class YouzengPsbcUpgradeBlockHook {

    private static final String POP_ID_UPGRADE = "1002";

    private static volatile boolean upgradeHooksInstalled = false;

    private YouzengPsbcUpgradeBlockHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PsbcConfig.ENABLE_BLOCK_APP_UPGRADE) {
            return;
        }
        if (!PsbcConfig.isTargetPackage(lpparam.packageName)) {
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (upgradeHooksInstalled) {
                    return;
                }
                Context ctx = (Context) param.args[0];
                if (!PsbcConfig.TARGET_PACKAGE.equals(ctx.getPackageName())) {
                    return;
                }
                try {
                    installAllHooks(cl);
                    upgradeHooksInstalled = true;
                    YouzengPsbcHelpers.log("[+] upgrade block hooks installed");
                } catch (Throwable t) {
                    YouzengPsbcHelpers.log("[-] upgrade block hooks install failed: " + t);
                    XposedBridge.log(t);
                }
            }
        });
    }

    private static void installAllHooks(ClassLoader cl) {
        installMainActivityHooks(cl);
        installPublicViewModelHooks(cl);
        installUpgradeMainCallBackHooks(cl);
        installMpaasCheckHooks(cl);
        installAppUpgradeUtilHooks(cl);
        installDialogManagerHooks(cl);
        installDialogUpdateApkHooks(cl);
        installDialogHomeManagerHooks(cl);
    }

    private static void installMainActivityHooks(ClassLoader cl) {
        try {
            Class<?> mainCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.home.view.activity.MainActivity", cl);
            XC_MethodReplacement block = new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("[+] MainActivity." + param.method.getName() + " blocked");
                    return null;
                }
            };
            XposedHelpers.findAndHookMethod(mainCls, "checkForAppUpgrade", block);
            XposedHelpers.findAndHookMethod(mainCls, "checkForUpgradeFromNewPlatform", block);
            XposedHelpers.findAndHookMethod(mainCls, "observeUpgrade", block);
            Class<?> upgradeResponseCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.model.api.UpgradeApi$Response", cl);
            Class<?> clientUpgradeResCls = XposedHelpers.findClass(
                    "com.alipay.mobileappcommon.biz.rpc.client.upgrade.model.ClientUpgradeRes", cl);
            XposedHelpers.findAndHookMethod(mainCls, "showDownloadDialog", upgradeResponseCls,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] MainActivity.showDownloadDialog(UpgradeApi.Response) blocked");
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(mainCls, "showDownloadDialog", clientUpgradeResCls,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] MainActivity.showDownloadDialog(ClientUpgradeRes) blocked");
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(mainCls, "showInstallDialog", String.class, boolean.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] MainActivity.showInstallDialog blocked path="
                                    + param.args[0]);
                            return null;
                        }
                    });
            YouzengPsbcHelpers.log("[+] MainActivity upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] MainActivity upgrade hooks: " + t);
        }
    }

    private static void installPublicViewModelHooks(ClassLoader cl) {
        try {
            Class<?> vmCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.viewmodel.PublicViewModel", cl);
            Class<?> upgradeApiCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.model.api.UpgradeApi", cl);
            XposedHelpers.findAndHookMethod(vmCls, "checkForUpgrade",
                    Context.class, upgradeApiCls, boolean.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] PublicViewModel.checkForUpgrade blocked");
                            return null;
                        }
                    });
            YouzengPsbcHelpers.log("[+] PublicViewModel upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] PublicViewModel upgrade hooks: " + t);
        }
    }

    private static void installUpgradeMainCallBackHooks(ClassLoader cl) {
        try {
            Class<?> cbCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.tools.upgrade.UpgradeMainCallBack", cl);
            Class<?> activityCls = android.app.Activity.class;
            Class<?> clientUpgradeResCls = XposedHelpers.findClass(
                    "com.alipay.mobileappcommon.biz.rpc.client.upgrade.model.ClientUpgradeRes", cl);
            XC_MethodReplacement blockUpgradeUi = new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("[+] UpgradeMainCallBack." + param.method.getName() + " blocked");
                    return null;
                }
            };
            XposedHelpers.findAndHookMethod(cbCls, "showUpgradeDialog",
                    activityCls, clientUpgradeResCls, boolean.class, blockUpgradeUi);
            XposedHelpers.findAndHookMethod(cbCls, "alreadyDownloaded",
                    activityCls, clientUpgradeResCls, boolean.class, blockUpgradeUi);
            YouzengPsbcHelpers.log("[+] UpgradeMainCallBack upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] UpgradeMainCallBack upgrade hooks: " + t);
        }
    }

    private static void installMpaasCheckHooks(ClassLoader cl) {
        try {
            Class<?> svcCls = XposedHelpers.findClass(
                    "com.alipay.mobile.upgrade.service.mpaas.impl.MPaaSCheckVersionServiceImpl", cl);
            XposedHelpers.findAndHookMethod(svcCls, "checkNewVersion", android.app.Activity.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] MPaaSCheckVersionServiceImpl.checkNewVersion blocked");
                            return null;
                        }
                    });
            YouzengPsbcHelpers.log("[+] MPaaSCheckVersionServiceImpl upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] MPaaSCheckVersionServiceImpl upgrade hooks: " + t);
        }
    }

    private static void installAppUpgradeUtilHooks(ClassLoader cl) {
        try {
            Class<?> utilCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.utils.AppUpgradeUtil", cl);
            Class<?> responseCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.model.api.UpgradeApi$Response", cl);
            XposedHelpers.findAndHookMethod(utilCls, "showVersionUpdateDialog",
                    Context.class, String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] AppUpgradeUtil.showVersionUpdateDialog blocked");
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(utilCls, "handleUmpResponse",
                    Context.class, responseCls, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] AppUpgradeUtil.handleUmpResponse blocked");
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(utilCls, "downloadApkFile",
                    Context.class, String.class, String.class, boolean.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] AppUpgradeUtil.downloadApkFile blocked");
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(utilCls, "showInstallDialog",
                    Context.class, String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] AppUpgradeUtil.showInstallDialog blocked");
                            return null;
                        }
                    });
            YouzengPsbcHelpers.log("[+] AppUpgradeUtil upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] AppUpgradeUtil upgrade hooks: " + t);
        }
    }

    private static void installDialogManagerHooks(ClassLoader cl) {
        try {
            Class<?> dialogCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.view.customview.dialog.DialogManager", cl);
            Class<?> ctxCls = Context.class;
            Class<?> listenerCls = android.view.View.OnClickListener.class;
            XposedHelpers.findAndHookMethod(dialogCls, "showDialogUpdateApk",
                    ctxCls, String.class, String.class, String.class, String.class,
                    listenerCls, listenerCls, boolean.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] DialogManager.showDialogUpdateApk blocked title="
                                    + param.args[1]);
                            return null;
                        }
                    });
            XposedHelpers.findAndHookMethod(dialogCls, "showDialogForceUpdateApk",
                    ctxCls, String.class, String.class, String.class,
                    listenerCls, boolean.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            YouzengPsbcHelpers.log("[+] DialogManager.showDialogForceUpdateApk blocked title="
                                    + param.args[1]);
                            return null;
                        }
                    });
            YouzengPsbcHelpers.log("[+] DialogManager upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] DialogManager upgrade hooks: " + t);
        }
    }

    private static void installDialogUpdateApkHooks(ClassLoader cl) {
        try {
            Class<?> dialogCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.view.customview.dialog.DialogUpdateApk", cl);
            XposedHelpers.findAndHookMethod(dialogCls, "show", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("[+] DialogUpdateApk.show blocked");
                    return null;
                }
            });
            YouzengPsbcHelpers.log("[+] DialogUpdateApk upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] DialogUpdateApk upgrade hooks: " + t);
        }
    }

    private static void installDialogHomeManagerHooks(ClassLoader cl) {
        try {
            Class<?> mgrCls = XposedHelpers.findClass(
                    "com.yitong.mbank.psbc.module.app.view.customview.dialog.config.DialogHomeManager", cl);
            XposedHelpers.findAndHookMethod(mgrCls, "setSpecialDialog",
                    Context.class, String.class, String.class, Object.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object popId = param.args[1];
                            if (POP_ID_UPGRADE.equals(popId)) {
                                YouzengPsbcHelpers.log("[+] DialogHomeManager.setSpecialDialog blocked popId="
                                        + popId);
                                param.setResult(null);
                            }
                        }
                    });
            YouzengPsbcHelpers.log("[+] DialogHomeManager upgrade hooks installed");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] DialogHomeManager upgrade hooks: " + t);
        }
    }
}
