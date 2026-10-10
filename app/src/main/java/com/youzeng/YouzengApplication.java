package com.youzeng;

import android.app.Application;

/**
 * 不在这里申请 su：Magisk 会把后台请求静默拒绝。
 * Root 授权只在 {@link MainActivity} 获得窗口焦点后发起。
 */
public class YouzengApplication extends Application {
}
