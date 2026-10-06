package com.aliahad.ainewshot

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.util.Log

/**
 * Xiaomi HyperOS / MIUI specifics, verified on HyperOS 4 (Android 17, Redmi K80 Pro).
 *
 * HyperOS layers its own per-app switches on top of Android's:
 *  - app-ops 100xx (from miui-framework MiuiHooks): 10008 AUTO_START, 10020 SHOW_WHEN_LOCKED,
 *    10021 BACKGROUND_START_ACTIVITY ("display pop-up windows while running in the background"),
 *    10023 SERVICE_FOREGROUND, 10026 SHOW_DEAMON_NOTIFICATION, 10014 EXACT_ALARM, 10007 BOOT_COMPLETED.
 *  - SystemUI notification flags per app/channel: float (heads-up), keyguard (lock screen), focus (Super Island),
 *    plus AI notification sorting that can fold "unimportant" apps. Third-party apps default to OFF for float.
 *    Only `canShowFocus` is readable by apps (content://miui.statusbar.notification.public); the rest have
 *    public settings screens (miui.settings.*_NOTIFICATION_SETTINGS).
 *  - Battery saver ("No restrictions") lives in Security › app details and isn't readable by apps.
 */
object HyperOS {
    const val OP_BOOT_COMPLETED = 10007
    const val OP_AUTO_START = 10008
    const val OP_EXACT_ALARM = 10014
    const val OP_SHOW_WHEN_LOCKED = 10020
    const val OP_BACKGROUND_START_ACTIVITY = 10021
    const val OP_SERVICE_FOREGROUND = 10023

    val isXiaomi: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** True/false when the op could be read, null when the ROM doesn't expose it. */
    fun opAllowed(ctx: Context, op: Int): Boolean? = try {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        val m = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        (m.invoke(aom, op, ctx.applicationInfo.uid, ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
    } catch (e: Throwable) {
        Log.i(TAG, "op $op not readable: ${e.javaClass.simpleName}")
        null
    }

    /** HyperOS focus notification protocol: 3 = Super Island (HyperOS 3+), 0 = unsupported. */
    fun focusProtocol(ctx: Context) = runCatching { SysSettings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0) }.getOrDefault(0)

    /** Whether SystemUI will render our focus notifications (Xiaomi gates this with a whitelist + per-app switch). */
    fun canShowFocus(ctx: Context): Boolean? = try {
        val b = ctx.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null,
            Bundle().apply { putString("package", ctx.packageName) })
        b?.getBoolean("canShowFocus", false)
    } catch (e: Throwable) { null }

    fun ignoringBattery(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    // MARK: Screens (all verified to exist on HyperOS 4)

    private fun pkgIntent(action: String, ctx: Context) = Intent(action).putExtra("package_name", ctx.packageName)
        .putExtra("extra_pkgname", ctx.packageName).putExtra("packageName", ctx.packageName)

    fun open(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        return false
    }

    fun appDetails(ctx: Context) = open(ctx,
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"))
            .putExtra("package_name", ctx.packageName).putExtra("package_label", "AI News Hot"),
        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    fun otherPermissions(ctx: Context) = open(ctx,
        Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            .putExtra("extra_pkgname", ctx.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName))
        || appDetails(ctx)

    fun autostart(ctx: Context) = open(ctx, pkgIntent("miui.intent.action.OP_AUTO_START", ctx)) || appDetails(ctx)
    fun floatingNotifications(ctx: Context) = open(ctx, pkgIntent("miui.settings.FLOAT_NOTIFICATION_SETTINGS", ctx)) || channelSettings(ctx, Alerts.CH_ALERTS)
    fun lockScreenNotifications(ctx: Context) = open(ctx, pkgIntent("miui.settings.LOCK_SCREEN_NOTIFICATION_SETTINGS", ctx)) || channelSettings(ctx, Alerts.CH_ALERTS)
    fun focusNotifications(ctx: Context) = open(ctx, pkgIntent("miui.settings.FOCUS_NOTIFICATION_SETTINGS", ctx))
    fun aiNotificationSorting(ctx: Context) = open(ctx, Intent("miui.settings.AI_NOTIFICATION_SETTINGS"))

    fun channelSettings(ctx: Context, channel: String) = open(ctx,
        Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(SysSettings.EXTRA_CHANNEL_ID, channel),
        Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName))

    fun overlay(ctx: Context) = open(ctx, Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
    fun fullScreen(ctx: Context) = Build.VERSION.SDK_INT >= 34 &&
        open(ctx, Intent(SysSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
    fun battery(ctx: Context) = open(ctx, Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))

    fun notificationsEnabled(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
