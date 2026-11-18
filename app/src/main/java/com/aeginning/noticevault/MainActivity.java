package com.aeginning.noticevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 210;
    private static final int CREATE_BACKUP = 301;
    private static final int OPEN_BACKUP = 302;
    private static final int REQUEST_DEVICE_UNLOCK = 910;
    private static final int BACKUP_SCHEMA_VERSION = 1;
    private static final int PAGE_SIZE = 30;
    private static final int MAX_BACKUP_RECORDS = 10000;
    private static final int MAX_BACKUP_BYTES = 50 * 1024 * 1024;

    private boolean darkTheme;
    private int COLOR_BG, COLOR_TEXT, COLOR_MUTED, COLOR_BLUE, COLOR_GREEN, COLOR_RED;
    private int COLOR_SURFACE, COLOR_BORDER, COLOR_TILE_BG, COLOR_TILE_BORDER;
    private int COLOR_DANGER_BG, COLOR_DANGER_BORDER, COLOR_DANGER_MUTED;
    private int COLOR_HINT, COLOR_ARROW, COLOR_BODY, COLOR_NEUTRAL_BORDER;
    private int COLOR_OK_BG, COLOR_ERR_BG, COLOR_CHIP_BG, COLOR_CHIP_BORDER;
    private int COLOR_LOAD_BORDER, COLOR_MESSAGE_BG, COLOR_EMPTY_ICON;

    private LinearLayout recordList;
    private LinearLayout selectionToolbar;
    private TextView selectionSummary;
    private TextView selectionDeleteAction;
    private TextView backupReminderBanner;
    private TextView recordCount;
    private EditText searchInput;
    private TextView notificationValue;
    private TextView shizukuServiceValue;
    private TextView shizukuPermissionValue;
    private TextView statusDetail;
    private TextView totalCountValue;
    private TextView todayCountValue;
    private LinearLayout filterRow;
    private TextView loadMoreView;
    private TextView appFilterView;
    private TextView dateFilterView;
    private String currentPackageFilter = "";
    private int currentDateFilterDays;
    private long currentDateStartTimestamp;
    private String shizukuMessage = "Shizuku 权限状态尚未检查";
    private char[] pendingPassword;
    private boolean backupRunning;
    private boolean destroyed;
    private boolean appUnlockedForSession;
    private boolean unlockRequestInProgress;
    private long backgroundAt;
    private String currentQuery = "";
    private String currentTypeFilter = "all";
    private long nextBeforeTimestamp;
    private long nextBeforeId;
    private int displayedRecordCount;
    private boolean hasMoreRecords;
    private boolean searchLoading;
    private boolean selectionMode;
    private final Set<Long> selectedRecordIds = new HashSet<>();
    private final List<VaultRecord> visibleRecords = new ArrayList<>();
    private final Map<Long, View> recordCardViews = new HashMap<>();

    private static final Typeface MEDIUM_TYPEFACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "NoticeVault-Search");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private Future<?> activeSearchFuture;
    private Runnable pendingSearch;
    private int searchGeneration;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) -> {
        if (code != REQ_PERMISSION) return;
        runOnUiThread(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) {
                shizukuMessage = "Shizuku 授权成功";
                toast("Shizuku 授权成功");
            } else {
                shizukuMessage = "Shizuku 授权被拒绝，请检查 Shizuku 应用管理";
                toast("Shizuku 授权未通过，请检查 Shizuku 应用管理");
            }
            refreshStatus();
        });
    };

    @Override
    public void onCreate(Bundle state) {
        String themeMode = VaultPreferences.getThemeMode(this);
        int uiNightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        darkTheme = "dark".equals(themeMode)
                || ("system".equals(themeMode)
                && uiNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        setTheme(darkTheme ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        applyColorPalette();
        Shizuku.addRequestPermissionResultListener(permissionListener);
        configureSystemBars();
        buildUi();
        refreshStatus();
    }

    private void applyColorPalette() {
        COLOR_BG = darkTheme ? Color.rgb(17, 23, 32) : Color.rgb(244, 247, 251);
        COLOR_TEXT = darkTheme ? Color.rgb(232, 238, 246) : Color.rgb(28, 42, 58);
        COLOR_MUTED = darkTheme ? Color.rgb(164, 177, 194) : Color.rgb(106, 121, 139);
        COLOR_BLUE = darkTheme ? Color.rgb(113, 174, 245) : Color.rgb(38, 105, 190);
        COLOR_GREEN = darkTheme ? Color.rgb(91, 202, 151) : Color.rgb(26, 142, 100);
        COLOR_RED = darkTheme ? Color.rgb(255, 133, 139) : Color.rgb(190, 65, 73);
        COLOR_SURFACE = darkTheme ? Color.rgb(29, 38, 51) : Color.WHITE;
        COLOR_BORDER = darkTheme ? Color.rgb(54, 67, 84) : 0xFFE8EDF3;
        COLOR_TILE_BG = darkTheme ? Color.rgb(36, 47, 62) : 0xFFF3F6FA;
        COLOR_TILE_BORDER = darkTheme ? Color.rgb(54, 67, 84) : 0xFFE9EEF4;
        COLOR_DANGER_BG = darkTheme ? Color.rgb(65, 37, 43) : 0xFFFFF8F8;
        COLOR_DANGER_BORDER = darkTheme ? Color.rgb(105, 58, 65) : 0xFFF0D9DB;
        COLOR_DANGER_MUTED = darkTheme ? Color.rgb(236, 160, 166) : 0xFF9B6A6E;
        COLOR_HINT = darkTheme ? Color.rgb(139, 154, 173) : 0xFF9BA9B8;
        COLOR_ARROW = darkTheme ? Color.rgb(142, 158, 178) : 0xFF9AA8B7;
        COLOR_BODY = darkTheme ? Color.rgb(205, 216, 229) : 0xFF44566B;
        COLOR_NEUTRAL_BORDER = darkTheme ? Color.rgb(57, 70, 88) : 0xFFE4EAF0;
        COLOR_OK_BG = darkTheme ? Color.rgb(29, 72, 58) : 0xFFE9F7F0;
        COLOR_ERR_BG = darkTheme ? Color.rgb(76, 39, 45) : 0xFFFFEEEE;
        COLOR_CHIP_BG = darkTheme ? Color.rgb(40, 52, 68) : 0xFFF1F4F8;
        COLOR_CHIP_BORDER = darkTheme ? Color.rgb(57, 70, 88) : 0xFFE1E7EE;
        COLOR_LOAD_BORDER = darkTheme ? Color.rgb(57, 70, 88) : 0xFFE2EAF2;
        COLOR_MESSAGE_BG = darkTheme ? Color.rgb(34, 69, 56) : 0xFFEAF7F0;
        COLOR_EMPTY_ICON = darkTheme ? Color.rgb(126, 145, 168) : 0xFF9EADBD;
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_SURFACE);
        int flags = 0;
        if (!darkTheme) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        buildHero(root);
        buildStatusCard(root);
        buildMetricsCard(root);
        buildBackupReminderBanner(root);
        // Keep recent retained messages above secondary configuration actions.
        buildRecordsSection(root);

        addSectionHeading(root, "权限与运行", "完成基础授权，让消息留存持续运行");
        addActionCard(root, "◉", "开启通知监听权限", "允许系统将可见通知交给本应用处理", COLOR_BLUE,
            this::openNotificationListenerSettings);
        addActionCard(root, "⌘", "检查 / 申请 Shizuku（可选）", "可选授权，不影响基础通知留存", COLOR_GREEN, this::requestShizuku);
        addActionCard(root, "⚙", "电池优化设置", "检查系统后台限制，减少进程被停止的情况", COLOR_MUTED, this::openBatterySettings);

        addSectionHeading(root, "隐私与通知来源", "选择保存哪些应用的通知，并控制本地数据保留时间");
        addActionCard(root, "▣", "通知来源管理", "默认仅保存微信通知，可按需添加其他应用", COLOR_BLUE, this::showSourcePicker);
        addActionCard(root, "◷", "自动清理期限", "可关闭，或设置保留 7 / 30 / 90 天", COLOR_MUTED, this::showRetentionDialog);
        addActionCard(root, "▤", "设备锁保护", "使用手机系统锁屏密码或生物识别验证", COLOR_GREEN, this::toggleAppLock);

        addSectionHeading(root, "外观与隐私", "调整界面显示方式，降低消息预览泄露风险");
        addActionCard(root, "◐", "界面主题", themeModeLabel(), COLOR_BLUE, this::showThemeDialog);
        addActionCard(root, "◌", "消息预览遮挡", previewPrivacyLabel(), COLOR_GREEN, this::showPreviewPrivacyDialog);

        addSectionHeading(root, "备份与数据", "备份文件使用密码加密，请妥善保管密码");
        addActionCard(root, "⇧", "导出加密备份", "将本机留存记录导出为 .nvbk 文件", COLOR_BLUE, () -> passwordDialog(true));
        addActionCard(root, "⇩", "导入加密备份", "从已有备份恢复记录", COLOR_GREEN, () -> passwordDialog(false));
        addActionCard(root, "◷", "自动备份提醒", backupReminderLabel(), COLOR_MUTED, this::showBackupReminderDialog);
        addActionCard(root, "✓", "检查本地数据完整性", "只读检查 SQLite 结构、密钥别名和存储占用", COLOR_GREEN, this::checkDatabaseHealth);
        addActionCard(root, "↻", "整理本地数据库", "回收空闲页面，尝试减少数据库文件占用", COLOR_MUTED, this::compactStorage);
        addDangerAction(root, "清空全部记录", "仅删除本机记录，不可撤销", this::confirmClearAll);

        setContentView(scroll);
    }

    private void buildHero(LinearLayout root) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(22), dp(22), dp(23));
        hero.setBackground(gradientBackground(
            new int[]{Color.rgb(24, 55, 91), Color.rgb(29, 105, 170)}, dp(24)));
        hero.setElevation(dp(3));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("微信防撤回");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView version = new TextView(this);
        version.setText("VERSION 2.2.0");
        version.setTextColor(Color.WHITE);
        version.setTextSize(10);
        version.setLetterSpacing(0.06f);
        version.setPadding(dp(10), dp(7), dp(10), dp(7));
        version.setBackground(roundBackground(0x2EFFFFFF, dp(30), 0x55FFFFFF, dp(1)));
        top.addView(version, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(top);

        TextView subtitle = new TextView(this);
        subtitle.setText("本地加密留存 · 快速检索 · 撤回提示 · 安全备份");
        subtitle.setTextColor(0xDDEAF5FF);
        subtitle.setTextSize(13);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(10);
        hero.addView(subtitle, subtitleLp);

        TextView note = new TextView(this);
        note.setText("只保存 Android 实际提供的通知内容；不读取微信私有数据库，也无法保证留存未显示为系统通知的消息");
        note.setTextColor(0xBBDDEBFA);
        note.setTextSize(11);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(15);
        hero.addView(note, noteLp);

        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.bottomMargin = dp(16);
        root.addView(hero, heroLp);
    }

    private void buildStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(14));
        card.setBackground(roundBackground(COLOR_SURFACE, dp(20), COLOR_BORDER, dp(1)));
        card.setElevation(dp(1));

        TextView heading = text("运行状态", 16, COLOR_TEXT, true);
        card.addView(heading);
        TextView hint = text("通知监听是核心权限；Shizuku 为可选状态辅助，不影响基础留存", 11, COLOR_MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(3);
        card.addView(hint, hintLp);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(-1, -2);
        tilesLp.topMargin = dp(14);
        card.addView(tiles, tilesLp);

        StatusTile notificationTile = createStatusTile("通知监听");
        StatusTile serviceTile = createStatusTile("Shizuku 服务");
        StatusTile permissionTile = createStatusTile("Shizuku 授权");
        notificationValue = notificationTile.value;
        shizukuServiceValue = serviceTile.value;
        shizukuPermissionValue = permissionTile.value;
        tiles.addView(notificationTile.container, weightedTileParams());
        tiles.addView(serviceTile.container, weightedTileParams());
        tiles.addView(permissionTile.container, weightedTileParams());

        statusDetail = text("正在读取状态…", 11, COLOR_MUTED, false);
        statusDetail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(12);
        card.addView(statusDetail, detailLp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(22);
        root.addView(card, cardLp);
    }

    private void buildBackupReminderBanner(LinearLayout root) {
        backupReminderBanner = text("", 12, COLOR_RED, true);
        backupReminderBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
        backupReminderBanner.setBackground(roundBackground(COLOR_DANGER_BG, dp(14), COLOR_DANGER_BORDER, dp(1)));
        backupReminderBanner.setClickable(true);
        backupReminderBanner.setFocusable(true);
        backupReminderBanner.setOnClickListener(view -> passwordDialog(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        root.addView(backupReminderBanner, lp);
        refreshBackupReminderBanner();
    }

    private void refreshBackupReminderBanner() {
        if (backupReminderBanner == null) return;
        if (!VaultPreferences.isBackupReminderDue(this, System.currentTimeMillis())) {
            backupReminderBanner.setVisibility(View.GONE);
            return;
        }
        long last = VaultPreferences.getLastSuccessfulBackup(this);
        int days = VaultPreferences.getBackupReminderDays(this);
        String message = last <= 0L
                ? "尚未完成成功备份 · 已开启每 " + days + " 天提醒，建议现在创建加密备份"
                : "备份提醒 · 距离上次成功备份已 "
                    + Math.max(0L, (System.currentTimeMillis() - last) / 86400000L)
                    + " 天，点击立即导出加密备份";
        backupReminderBanner.setText(message);
        backupReminderBanner.setVisibility(View.VISIBLE);
    }

    private String backupReminderLabel() {
        int days = VaultPreferences.getBackupReminderDays(this);
        if (days == 0) return "未开启 · 可设置 1–365 天的提醒周期";
        long last = VaultPreferences.getLastSuccessfulBackup(this);
        if (last <= 0L) return "每 " + days + " 天提醒 · 尚无成功备份记录";
        String date = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", last).toString();
        return "每 " + days + " 天提醒 · 上次成功备份 " + date;
    }

    private void showBackupReminderDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(2);
        input.setHint("输入 1–365 天");
        int current = VaultPreferences.getBackupReminderDays(this);
        input.setText(String.valueOf(current == 0 ? 7 : current));
        input.setSelection(input.length());
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(20), dp(4), dp(20), 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自动备份提醒")
                .setMessage("设置距离上次成功导出加密备份的间隔。提醒会在你打开应用时显示，不会在后台弹出系统通知。")
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setNeutralButton("关闭提醒", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                VaultPreferences.setBackupReminderDays(this, 0);
                refreshBackupReminderBanner();
                toast("自动备份提醒已关闭");
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                int days;
                try {
                    days = Integer.parseInt(value);
                } catch (NumberFormatException error) {
                    toast("请输入 1–365 之间的整数天数");
                    return;
                }
                if (days < 1 || days > 365) {
                    toast("提醒周期必须为 1–365 天");
                    return;
                }
                VaultPreferences.setBackupReminderDays(this, days);
                refreshBackupReminderBanner();
                toast("已设置每 " + days + " 天进行备份提醒");
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private LinearLayout buildMetricsCardPlaceholder() {
        return new LinearLayout(this);
    }

    private void buildMetricsCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(roundBackground(COLOR_SURFACE, dp(18), COLOR_BORDER, dp(1)));
        LinearLayout total = createMetricTile("累计留存", "读取中");
        LinearLayout today = createMetricTile("今日新增", "读取中");
        totalCountValue = (TextView) total.getTag();
        todayCountValue = (TextView) today.getTag();
        card.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(today, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(22);
        root.addView(card, lp);
    }

    private LinearLayout createMetricTile(String label, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        TextView title = text(label, 11, COLOR_MUTED, false);
        TextView number = text(value, 21, COLOR_TEXT, true);
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(-1, -2);
        numberLp.topMargin = dp(5);
        number.setGravity(Gravity.CENTER);
        title.setGravity(Gravity.CENTER);
        tile.addView(title);
        tile.addView(number, numberLp);
        tile.setTag(number);
        return tile;
    }

    private void refreshMetrics() {
        if (destroyed || totalCountValue == null || todayCountValue == null) return;
        ioExecutor.execute(() -> {
            long total = 0L;
            long today = 0L;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                total = db.count();
                java.util.Calendar calendar = java.util.Calendar.getInstance();
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
                calendar.set(java.util.Calendar.MINUTE, 0);
                calendar.set(java.util.Calendar.SECOND, 0);
                calendar.set(java.util.Calendar.MILLISECOND, 0);
                today = db.countSince(calendar.getTimeInMillis());
            } catch (Exception error) {
                // The primary record list reports detailed read errors separately.
            }
            final long totalResult = total;
            final long todayResult = today;
            mainHandler.post(() -> {
                if (destroyed || totalCountValue == null || todayCountValue == null) return;
                totalCountValue.setText(String.valueOf(totalResult));
                todayCountValue.setText(String.valueOf(todayResult));
            });
        });
    }

    private StatusTile createStatusTile(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(4), dp(10), dp(4), dp(10));
        box.setBackground(roundBackground(COLOR_TILE_BG, dp(13), COLOR_TILE_BORDER, dp(1)));

        TextView labelView = text(label, 10, COLOR_MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        box.addView(labelView);

        TextView value = text("检查中", 12, COLOR_TEXT, true);
        value.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(-1, -2);
        valueLp.topMargin = dp(4);
        box.addView(value, valueLp);
        return new StatusTile(box, value);
    }

    private LinearLayout.LayoutParams weightedTileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private void addSectionHeading(LinearLayout root, String title, String subtitle) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 17, COLOR_TEXT, true);
        block.addView(titleView);
        TextView subView = text(subtitle, 11, COLOR_MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(3);
        block.addView(subView, subLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(11);
        root.addView(block, lp);
    }

    private void addActionCard(LinearLayout parent, String symbol, String title, String subtitle,
                               int accent, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(roundBackground(COLOR_SURFACE, dp(17), COLOR_BORDER, dp(1)));
        card.setElevation(dp(1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());

        TextView icon = text(symbol, 20, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundBackground(withAlpha(accent, 0.11f), dp(13), Color.TRANSPARENT, 0));
        card.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1);
        labelsLp.leftMargin = dp(13);
        labelsLp.rightMargin = dp(8);
        card.addView(labels, labelsLp);
        labels.addView(text(title, 14, COLOR_TEXT, true));
        TextView sub = text(subtitle, 11, COLOR_MUTED, false);
        sub.setMaxLines(2);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(4);
        labels.addView(sub, subLp);

        TextView arrow = text("›", 25, COLOR_ARROW, false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(20), -2));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(9);
        parent.addView(card, cardLp);
    }

    private void addDangerAction(LinearLayout parent, String title, String subtitle, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(roundBackground(COLOR_DANGER_BG, dp(16), COLOR_DANGER_BORDER, dp(1)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        card.addView(text(title, 14, COLOR_RED, true));
        TextView sub = text(subtitle, 10, COLOR_DANGER_MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(3);
        card.addView(sub, subLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(25);
        parent.addView(card, lp);
    }

    private void buildRecordsSection(LinearLayout root) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("消息记录", 18, COLOR_TEXT, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        recordCount = text("准备就绪", 11, COLOR_MUTED, false);
        recordCount.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(recordCount);
        root.addView(heading);

        LinearLayout searchCard = new LinearLayout(this);
        searchCard.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.setGravity(Gravity.CENTER_VERTICAL);
        searchCard.setPadding(dp(13), 0, dp(13), 0);
        searchCard.setBackground(roundBackground(COLOR_SURFACE, dp(15), COLOR_BORDER, dp(1)));
        TextView searchIcon = text("⌕", 22, COLOR_MUTED, true);
        searchIcon.setGravity(Gravity.CENTER);
        searchCard.addView(searchIcon, new LinearLayout.LayoutParams(dp(25), dp(50)));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setHintTextColor(COLOR_HINT);
        searchInput.setHint("搜索会话、标题或消息内容");
        searchInput.setPadding(dp(7), 0, dp(5), 0);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchCard.addView(searchInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.topMargin = dp(12);
        root.addView(searchCard, searchLp);

        TextView hint = text("每页 30 条 · 点击查看详情 · 长按消息进入多选 · 星标收藏重要消息", 10, COLOR_MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(6);
        hintLp.bottomMargin = dp(9);
        root.addView(hint, hintLp);

        filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterRow.setPadding(0, dp(2), 0, dp(8));
        addFilterChip("全部", "all");
        addFilterChip("普通消息", VaultRecord.MESSAGE);
        addFilterChip("撤回提示", VaultRecord.REVOKE);
        addFilterChip("收藏", "favorite");
        root.addView(filterRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout advancedFilters = new LinearLayout(this);
        advancedFilters.setOrientation(LinearLayout.HORIZONTAL);
        advancedFilters.setGravity(Gravity.CENTER_VERTICAL);
        advancedFilters.setPadding(0, 0, 0, dp(9));
        appFilterView = createFilterAction("应用：全部", this::showAppFilterDialog);
        dateFilterView = createFilterAction("时间：全部", this::showDateFilterDialog);
        LinearLayout.LayoutParams appFilterLp = new LinearLayout.LayoutParams(0, -2, 1);
        appFilterLp.rightMargin = dp(6);
        advancedFilters.addView(appFilterView, appFilterLp);
        advancedFilters.addView(dateFilterView, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(advancedFilters, new LinearLayout.LayoutParams(-1, -2));
        updateAdvancedFilterLabels();

        selectionToolbar = new LinearLayout(this);
        selectionToolbar.setOrientation(LinearLayout.HORIZONTAL);
        selectionToolbar.setGravity(Gravity.CENTER_VERTICAL);
        selectionToolbar.setPadding(0, 0, 0, dp(8));
        selectionSummary = text("已选择 0 条", 11, COLOR_MUTED, true);
        selectionToolbar.addView(selectionSummary, new LinearLayout.LayoutParams(0, -2, 1));
        TextView selectAll = createFilterAction("全选已加载", this::selectAllLoadedRecords);
        selectionToolbar.addView(selectAll, new LinearLayout.LayoutParams(-2, -2));
        selectionDeleteAction = createFilterAction("删除 (0)", this::confirmDeleteSelectedRecords);
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(-2, -2);
        deleteLp.leftMargin = dp(5);
        selectionToolbar.addView(selectionDeleteAction, deleteLp);
        TextView exitSelection = createFilterAction("退出", this::exitSelectionMode);
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-2, -2);
        exitLp.leftMargin = dp(5);
        selectionToolbar.addView(exitSelection, exitLp);
        selectionToolbar.setVisibility(View.GONE);
        root.addView(selectionToolbar, new LinearLayout.LayoutParams(-1, -2));

        recordList = new LinearLayout(this);
        recordList.setOrientation(LinearLayout.VERTICAL);
        root.addView(recordList, new LinearLayout.LayoutParams(-1, -2));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                scheduleSearch(s.toString());
            }
            @Override public void afterTextChanged(Editable editable) { }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearch(searchInput.getText().toString());
                return true;
            }
            return false;
        });
    }

    /**
     * Open the system notification-listener permission page.
     * Some vendor ROMs do not expose the dedicated settings activity; fall back
     * to the main Settings screen instead of letting ActivityNotFoundException
     * crash the app.
     */
    private void openNotificationListenerSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException error) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast("系统未提供通知监听专用页面，已打开系统设置，请手动搜索“通知使用权”或“通知访问权限”");
            } catch (RuntimeException fallbackError) {
                toast("无法打开系统设置，请在手机设置中搜索“通知使用权”或“通知访问权限”");
            }
        }
    }

    private void refreshStatus() {
        if (notificationValue == null) return;
        boolean shizukuAlive = false;
        boolean shizukuGranted = false;
        try {
            shizukuAlive = Shizuku.pingBinder();
            shizukuGranted = shizukuAlive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            shizukuMessage = "状态读取异常：" + error.getClass().getSimpleName();
        }

        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean listenerEnabled = false;
        if (enabled != null) {
            ComponentName expected = new ComponentName(this, VaultNotificationListener.class);
            for (String entry : enabled.split(":")) {
                ComponentName component = ComponentName.unflattenFromString(entry);
                if (expected.equals(component)) {
                    listenerEnabled = true;
                    break;
                }
            }
        }

        updateStatusTile(notificationValue, "通知监听", listenerEnabled);
        updateStatusTile(shizukuServiceValue, "Shizuku 服务", shizukuAlive);
        updateStatusTile(shizukuPermissionValue, "Shizuku 授权", shizukuGranted);

        if (!shizukuAlive) markOptionalStatus(shizukuServiceValue);
        if (!shizukuGranted) markOptionalStatus(shizukuPermissionValue);
        String detail = listenerEnabled
                ? "通知监听已开启，消息留存正常工作。Shizuku 是可选扩展。"
                : "请先开启 Android 通知监听权限；Shizuku 为可选功能，不影响基础通知留存。";
        if (shizukuGranted) detail += " Shizuku 授权通过。";
        long droppedNotifications = VaultPreferences.getDroppedNotificationCount(this);
        if (droppedNotifications > 0L) {
            detail += "\n警告：通知队列累计有 " + droppedNotifications
                    + " 条通知未能排队；这些通知无法由应用补回。";
        }
        statusDetail.setText(detail + "\n仅保存系统实际交付的通知内容，不读取微信私有数据库。");
    }

    private void markOptionalStatus(TextView value) {
        value.setText("可选");
        value.setTextColor(COLOR_MUTED);
        value.setBackground(roundBackground(COLOR_CHIP_BG, dp(30), COLOR_NEUTRAL_BORDER, dp(1)));
        value.setPadding(dp(7), dp(4), dp(7), dp(4));
    }

    private void updateStatusTile(TextView value, String label, boolean active) {
        value.setText(active ? "已开启" : "未开启");
        value.setTextColor(active ? COLOR_GREEN : COLOR_RED);
        value.setBackground(roundBackground(active ? COLOR_OK_BG : COLOR_ERR_BG, dp(30), Color.TRANSPARENT, 0));
        value.setPadding(dp(7), dp(4), dp(7), dp(4));
    }

    private String themeModeLabel() {
        String mode = VaultPreferences.getThemeMode(this);
        if ("dark".equals(mode)) return "深色模式";
        if ("light".equals(mode)) return "浅色模式";
        return "跟随系统";
    }

    private String previewPrivacyLabel() {
        return VaultPreferences.isPreviewHidden(this)
                ? "列表中的会话、标题和正文预览已遮挡"
                : "列表默认显示消息预览";
    }

    private void showThemeDialog() {
        String[] labels = new String[]{"跟随系统", "浅色模式", "深色模式"};
        String[] values = new String[]{"system", "light", "dark"};
        String current = VaultPreferences.getThemeMode(this);
        int selected = "light".equals(current) ? 1 : ("dark".equals(current) ? 2 : 0);
        new AlertDialog.Builder(this)
                .setTitle("界面主题")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    VaultPreferences.setThemeMode(this, values[which]);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPreviewPrivacyDialog() {
        boolean hidden = VaultPreferences.isPreviewHidden(this);
        new AlertDialog.Builder(this)
                .setTitle("消息预览遮挡")
                .setMessage(hidden
                        ? "当前列表已隐藏会话、标题和消息正文。点击记录仍可查看完整详情。"
                        : "开启后，列表不显示会话名称、标题和消息正文预览；点击记录仍可查看完整详情。")
                .setNegativeButton("取消", null)
                .setPositiveButton(hidden ? "关闭遮挡" : "开启遮挡", (dialog, which) -> {
                    VaultPreferences.setPreviewHidden(this, !hidden);
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                    toast(!hidden ? "消息预览遮挡已开启" : "消息预览遮挡已关闭");
                })
                .show();
    }

    private TextView createFilterAction(String label, Runnable action) {
        TextView view = text(label, 11, COLOR_MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackground(roundBackground(COLOR_CHIP_BG, dp(12), COLOR_CHIP_BORDER, dp(1)));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void updateAdvancedFilterLabels() {
        if (appFilterView != null) {
            appFilterView.setText(currentPackageFilter.isEmpty()
                    ? "应用：全部" : "应用：" + getPackageLabel(currentPackageFilter));
        }
        if (dateFilterView != null) {
            dateFilterView.setText(currentDateFilterDays == 0 ? "时间：全部"
                    : currentDateFilterDays == 1 ? "时间：24 小时"
                    : "时间：最近 " + currentDateFilterDays + " 天");
        }
    }

    private String getPackageLabel(String packageName) {
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException error) {
            return packageName;
        }
    }

    private void showAppFilterDialog() {
        Set<String> allowed = VaultPreferences.getAllowedPackages(this);
        List<AppChoice> choices = new ArrayList<>();
        for (String packageName : allowed) {
            if (packageName == null || packageName.trim().isEmpty()) continue;
            choices.add(new AppChoice(packageName, getPackageLabel(packageName)));
        }
        Collections.sort(choices, Comparator.comparing(
                choice -> choice.label.toLowerCase(java.util.Locale.ROOT)));
        CharSequence[] labels = new CharSequence[choices.size() + 1];
        labels[0] = "全部应用";
        for (int i = 0; i < choices.size(); i++) labels[i + 1] = choices.get(i).label;
        int selected = 0;
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).packageName.equals(currentPackageFilter)) selected = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("按通知来源筛选")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    currentPackageFilter = which == 0 ? "" : choices.get(which - 1).packageName;
                    updateAdvancedFilterLabels();
                    dialog.dismiss();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDateFilterDialog() {
        int[] days = new int[]{0, 1, 7, 30};
        String[] labels = new String[]{"全部时间", "最近 24 小时", "最近 7 天", "最近 30 天"};
        int selected = 0;
        for (int i = 0; i < days.length; i++) if (days[i] == currentDateFilterDays) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("按时间筛选")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    currentDateFilterDays = days[which];
                    updateAdvancedFilterLabels();
                    dialog.dismiss();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSourcePicker() {
        PackageManager packageManager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchable = packageManager.queryIntentActivities(launcherIntent, 0);
        List<AppChoice> choices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : launchable) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.equals(getPackageName()) || !seen.add(packageName)) continue;
            CharSequence label = info.loadLabel(packageManager);
            choices.add(new AppChoice(packageName, label == null ? packageName : label.toString()));
        }
        try {
            android.content.pm.ApplicationInfo wechat = packageManager.getApplicationInfo(VaultPreferences.WECHAT_PACKAGE, 0);
            if (seen.add(VaultPreferences.WECHAT_PACKAGE)) {
                CharSequence label = packageManager.getApplicationLabel(wechat);
                choices.add(new AppChoice(VaultPreferences.WECHAT_PACKAGE,
                        label == null ? "微信" : label.toString()));
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // WeChat is not installed; other launchable applications remain selectable.
        }
        Collections.sort(choices, Comparator.comparing((AppChoice choice) -> choice.label.toLowerCase(java.util.Locale.ROOT)));
        if (choices.isEmpty()) {
            toast("未找到可选应用，请检查系统应用列表权限");
            return;
        }

        Set<String> selected = new HashSet<>(VaultPreferences.getAllowedPackages(this));
        CharSequence[] labels = new CharSequence[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            AppChoice choice = choices.get(i);
            labels[i] = choice.label + "  ·  " + choice.packageName;
            checked[i] = selected.contains(choice.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("允许留存通知的应用")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String packageName = choices.get(which).packageName;
                    if (isChecked) selected.add(packageName);
                    else selected.remove(packageName);
                })
                .setNeutralButton("仅微信", (dialog, which) -> {
                    Set<String> defaults = new HashSet<>();
                    defaults.add(VaultPreferences.WECHAT_PACKAGE);
                    VaultPreferences.setAllowedPackages(this, defaults);
                    toast("已恢复为仅保存微信通知");
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    VaultPreferences.setAllowedPackages(this, selected);
                    toast("通知来源设置已保存：" + selected.size() + " 个应用");
                })
                .show();
    }

    private void showRetentionDialog() {
        final int[] retentionDays = new int[]{0, 7, 30, 90};
        CharSequence[] labels = new CharSequence[]{"关闭自动清理（永久保留）", "保留 7 天", "保留 30 天", "保留 90 天"};
        int current = VaultPreferences.getRetentionDays(this);
        int selected = 0;
        for (int i = 0; i < retentionDays.length; i++) if (retentionDays[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("自动清理期限")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    VaultPreferences.setRetentionDays(this, retentionDays[which]);
                    dialog.dismiss();
                    toast(retentionDays[which] == 0 ? "已关闭自动清理" : "已设置保留 " + retentionDays[which] + " 天");
                    refreshMetrics();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toggleAppLock() {
        if (VaultPreferences.isAppLockEnabled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("关闭设备锁保护")
                    .setMessage("关闭后，打开应用时不再要求系统锁屏凭据验证。")
                    .setNegativeButton("保留保护", null)
                    .setPositiveButton("关闭保护", (dialog, which) -> {
                        VaultPreferences.setAppLockEnabled(this, false);
                        appUnlockedForSession = false;
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                        toast("设备锁保护已关闭");
                        refreshMetrics();
                        scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                    })
                    .show();
            return;
        }

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            toast("请先在手机系统设置中设置锁屏密码、图案或生物识别，再启用应用锁");
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) { }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("启用设备锁保护")
                .setMessage("每次冷启动或离开应用超过 60 秒后，使用手机系统锁屏凭据验证。应用会隐藏最近任务缩略图。")
                .setNegativeButton("取消", null)
                .setPositiveButton("启用", (dialog, which) -> {
                    VaultPreferences.setAppLockEnabled(this, true);
                    appUnlockedForSession = false;
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    enforceAppLock();
                })
                .show();
    }

    private void enforceAppLock() {
        if (!VaultPreferences.isAppLockEnabled(this)) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (appUnlockedForSession || unlockRequestInProgress) return;

        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard == null || !keyguard.isDeviceSecure()) {
            VaultPreferences.setAppLockEnabled(this, false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            toast("系统锁屏凭据已移除，应用锁保护已关闭");
            return;
        }
        Intent credentialIntent = keyguard.createConfirmDeviceCredentialIntent(
                "解锁微信防撤回", "请验证手机锁屏凭据以访问本地消息");
        if (credentialIntent == null) {
            VaultPreferences.setAppLockEnabled(this, false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            toast("系统未提供凭据验证界面，应用锁已关闭");
            return;
        }
        try {
            unlockRequestInProgress = true;
            startActivityForResult(credentialIntent, REQUEST_DEVICE_UNLOCK);
        } catch (Exception error) {
            unlockRequestInProgress = false;
            toast("无法启动系统身份验证，请检查手机锁屏设置");
        }
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuMessage = "Shizuku 服务未连接";
                toast("Shizuku 未运行，请先启动 Shizuku 服务");
                refreshStatus();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuMessage = "Shizuku 已授权";
                toast("Shizuku 已授权");
                refreshStatus();
                return;
            }
            shizukuMessage = "已发送授权请求，等待 Shizuku 返回结果";
            refreshStatus();
            Shizuku.requestPermission(REQ_PERMISSION);
        } catch (Throwable error) {
            shizukuMessage = "Shizuku 检查或申请失败：" + error.getClass().getSimpleName();
            toast("Shizuku 状态检查失败：" + error.getMessage());
            refreshStatus();
        }
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception error) {
            toast("当前系统不支持该设置入口");
        }
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle("清空本机记录")
            .setMessage("确定删除本机全部留存记录？此操作不可撤销，建议先导出加密备份。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清空", (dialog, which) -> clearAllRecords())
            .show();
    }

    private void checkDatabaseHealth() {
        toast("正在执行只读数据完整性检查");
        ioExecutor.execute(() -> {
            String result;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                long records = db.count();
                long storage = db.storageBytes();
                String integrity = db.verifyIntegrity();
                boolean keyAliasPresent = VaultCrypto.hasKeyAlias();
                if (!"ok".equalsIgnoreCase(integrity)) {
                    result = "数据库结构检查未通过：SQLite 返回 " + integrity
                            + "。应用未自动修复或修改数据，请先保留现状并导出/复制可用备份。";
                } else if (records > 0L && !keyAliasPresent) {
                    result = "严重警告：本机有 " + records + " 条记录，但 Android Keystore 密钥别名缺失。"
                            + "应用已禁止自动创建替代密钥；请勿卸载应用或清除应用数据。";
                } else {
                    result = "SQLite 结构检查正常；记录 " + records + " 条；数据库及 WAL 占用 "
                            + formatBytes(storage) + "。密钥别名"
                            + (keyAliasPresent ? "存在" : "尚未创建")
                            + "（别名存在不代表所有密文均已逐条验证）。";
                }
            } catch (Exception error) {
                result = "完整性检查无法完成：" + safe(error.getMessage())
                        + "。应用未执行修复或清空操作。";
            }
            final String message = result;
            mainHandler.post(() -> {
                if (!destroyed) new AlertDialog.Builder(MainActivity.this)
                        .setTitle("本地数据检查")
                        .setMessage(message)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        });
    }

    private void compactStorage() {
        toast("正在整理数据库，请保持应用运行");
        ioExecutor.execute(() -> {
            String result;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                long[] sizes = db.compactStorage();
                result = "整理完成：数据库 " + formatBytes(sizes[0]) + " → " + formatBytes(sizes[1]);
            } catch (Exception error) {
                result = "整理未完成：数据库正被其他操作占用，请稍后重试";
            }
            final String message = result;
            mainHandler.post(() -> {
                if (!destroyed) toast(message);
            });
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void clearAllRecords() {
        recordCount.setText("正在清空…");
        ioExecutor.execute(() -> {
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                db.clearAll();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    toast("本机记录已清空");
                    refreshMetrics();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (!destroyed) toast("清空失败：" + error.getMessage());
                });
            }
        });
    }

    private void enterSelectionMode(long recordId) {
        if (recordId <= 0L) return;
        selectionMode = true;
        toggleRecordSelection(recordId);
    }

    private void toggleRecordSelection(long recordId) {
        if (recordId <= 0L) return;
        if (selectedRecordIds.contains(recordId)) {
            selectedRecordIds.remove(recordId);
        } else {
            if (selectedRecordIds.size() >= 100) {
                toast("一次最多选择 100 条记录，请分批删除");
                return;
            }
            selectedRecordIds.add(recordId);
        }
        refreshSelectionToolbar();
        refreshRecordCardStyles();
    }

    private void selectAllLoadedRecords() {
        selectionMode = true;
        for (VaultRecord record : visibleRecords) {
            if (record.id > 0L && selectedRecordIds.size() < 100) selectedRecordIds.add(record.id);
        }
        refreshSelectionToolbar();
        refreshRecordCardStyles();
        if (visibleRecords.size() > 100) toast("当前已加载超过 100 条，仅选择前 100 条");
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedRecordIds.clear();
        refreshSelectionToolbar();
        refreshRecordCardStyles();
    }

    private void refreshSelectionToolbar() {
        if (selectionToolbar == null) return;
        selectionToolbar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        if (selectionSummary != null) selectionSummary.setText("已选择 " + selectedRecordIds.size() + " 条");
        if (selectionDeleteAction != null) {
            selectionDeleteAction.setText("删除 (" + selectedRecordIds.size() + ")");
            selectionDeleteAction.setEnabled(!selectedRecordIds.isEmpty());
            selectionDeleteAction.setAlpha(selectedRecordIds.isEmpty() ? 0.45f : 1f);
        }
    }

    private void refreshRecordCardStyles() {
        for (Map.Entry<Long, View> entry : recordCardViews.entrySet()) {
            boolean selected = selectedRecordIds.contains(entry.getKey());
            entry.getValue().setBackground(roundBackground(
                    selected ? COLOR_TILE_BG : COLOR_SURFACE, dp(16),
                    selected ? COLOR_BLUE : COLOR_BORDER, selected ? dp(2) : dp(1)));
        }
    }

    private void confirmDeleteSelectedRecords() {
        if (selectedRecordIds.isEmpty()) {
            toast("请先长按消息并选择要删除的记录");
            return;
        }
        int count = selectedRecordIds.size();
        new AlertDialog.Builder(this)
                .setTitle("确认批量删除")
                .setMessage("即将永久删除已选择的 " + count
                        + " 条本地记录。此操作无法撤销，未选择的记录不会受到影响。建议先导出加密备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (dialog, which) -> deleteSelectedRecords())
                .show();
    }

    private void deleteSelectedRecords() {
        if (selectedRecordIds.isEmpty()) return;
        List<Long> ids = new ArrayList<>(selectedRecordIds);
        selectionSummary.setText("正在删除 " + ids.size() + " 条…");
        ioExecutor.execute(() -> {
            int deleted = 0;
            String failure = null;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                deleted = db.deleteRecords(ids);
            } catch (Exception error) {
                failure = error.getMessage();
            }
            final int deletedCount = deleted;
            final String errorMessage = failure;
            mainHandler.post(() -> {
                if (destroyed) return;
                selectionMode = false;
                selectedRecordIds.clear();
                refreshSelectionToolbar();
                if (errorMessage == null) {
                    toast("已删除 " + deletedCount + " 条记录");
                    refreshMetrics();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                } else {
                    toast("批量删除失败：" + safe(errorMessage));
                    refreshSelectionToolbar();
                }
            });
        });
    }

    private void toggleFavorite(VaultRecord record, TextView action) {
        if (record == null || record.id <= 0L || action == null) return;
        boolean target = !record.favorite;
        action.setEnabled(false);
        try {
            ioExecutor.execute(() -> {
                boolean saved = false;
                String errorText = null;
                try (VaultDb db = new VaultDb(getApplicationContext())) {
                    saved = db.setFavorite(record.id, target);
                } catch (Exception error) {
                    errorText = error.getMessage();
                }
                final boolean success = saved;
                final String failure = errorText;
                mainHandler.post(() -> {
                    if (destroyed) return;
                    action.setEnabled(true);
                    if (!success) {
                        toast("收藏状态保存失败：" + safe(failure));
                        return;
                    }
                    record.favorite = target;
                    action.setText(target ? "★" : "☆");
                    action.setTextColor(target ? COLOR_BLUE : COLOR_MUTED);
                    action.setContentDescription(target ? "取消收藏" : "收藏消息");
                    toast(target ? "已收藏这条消息" : "已取消收藏");
                    if ("favorite".equals(currentTypeFilter) && !target) {
                        scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                    }
                });
            });
        } catch (RuntimeException rejected) {
            action.setEnabled(true);
            toast("收藏任务无法启动，请稍后重试");
        }
    }

    private void addFilterChip(String label, String value) {
        TextView chip = text(label, 11, COLOR_MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(13), dp(8), dp(13), dp(8));
        chip.setBackground(roundBackground(COLOR_CHIP_BG, dp(30), COLOR_CHIP_BORDER, dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(7);
        filterRow.addView(chip, lp);
        chip.setTag(value);
        chip.setOnClickListener(view -> {
            String next = (String) view.getTag();
            if (!next.equals(currentTypeFilter)) {
                currentTypeFilter = next;
                refreshFilterChips();
                scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
            }
        });
        refreshFilterChips();
    }

    private void refreshFilterChips() {
        if (filterRow == null) return;
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            TextView chip = (TextView) filterRow.getChildAt(i);
            boolean selected = currentTypeFilter.equals(chip.getTag());
            chip.setTextColor(selected ? (darkTheme ? Color.rgb(17, 23, 32) : Color.WHITE) : COLOR_MUTED);
            chip.setBackground(roundBackground(
                    selected ? COLOR_BLUE : COLOR_CHIP_BG, dp(30),
                    selected ? COLOR_BLUE : COLOR_CHIP_BORDER, dp(1)));
        }
    }

    private void scheduleSearch(String query) {
        if (destroyed || recordList == null) return;
        currentQuery = query == null ? "" : query.trim();
        selectedRecordIds.clear();
        selectionMode = false;
        visibleRecords.clear();
        recordCardViews.clear();
        refreshSelectionToolbar();
        nextBeforeTimestamp = 0L;
        nextBeforeId = 0L;
        displayedRecordCount = 0;
        hasMoreRecords = false;
        searchLoading = false;
        final int generation = ++searchGeneration;

        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        if (activeSearchFuture != null) activeSearchFuture.cancel(true);
        recordList.removeAllViews();
        if (loadMoreView != null) loadMoreView.setVisibility(View.GONE);
        recordCount.setText("等待检索…");
        pendingSearch = () -> {
            pendingSearch = null;
            currentDateStartTimestamp = RecordFilter.startTimestamp(
                    currentDateFilterDays, System.currentTimeMillis());
            loadRecords(currentQuery, currentTypeFilter, currentPackageFilter,
                    currentDateStartTimestamp, 0L, 0L, generation, false);
        };
        mainHandler.postDelayed(pendingSearch, 220);
    }

    private void loadMoreRecords() {
        if (destroyed || searchLoading || !hasMoreRecords) return;
        loadRecords(currentQuery, currentTypeFilter, currentPackageFilter,
                currentDateStartTimestamp, nextBeforeTimestamp, nextBeforeId,
                searchGeneration, true);
    }

    private void loadRecords(String query, String typeFilter, String packageFilter,
                             long fromTimestamp, long beforeTimestamp, long beforeId,
                             int generation, boolean append) {
        if (searchLoading || generation != searchGeneration) return;
        searchLoading = true;
        if (loadMoreView != null && loadMoreView.getParent() == recordList) {
            loadMoreView.setText("正在加载…");
            loadMoreView.setEnabled(false);
        } else if (!append) {
            recordCount.setText("正在检索…");
        }

        activeSearchFuture = searchExecutor.submit(() -> {
            List<VaultRecord> records = null;
            String errorMessage = null;
            boolean more = false;
            long cursorTimestamp = 0L;
            long cursorId = 0L;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                if (!append) {
                    VaultPreferences.maybeCleanupExpired(getApplicationContext(), db, System.currentTimeMillis());
                }
                records = db.searchPage(query, typeFilter, packageFilter, fromTimestamp,
                        beforeTimestamp, beforeId, PAGE_SIZE + 1);
                if (records.size() > PAGE_SIZE) {
                    more = true;
                    records.remove(records.size() - 1);
                }
                if (!records.isEmpty()) {
                    VaultRecord last = records.get(records.size() - 1);
                    cursorTimestamp = last.timestamp;
                    cursorId = last.id;
                }
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                errorMessage = error.getMessage();
            }

            final List<VaultRecord> result = records;
            final String failure = errorMessage;
            final boolean canLoadMore = more;
            final long lastTimestamp = cursorTimestamp;
            final long lastId = cursorId;
            mainHandler.post(() -> {
                if (destroyed || generation != searchGeneration) return;
                searchLoading = false;
                activeSearchFuture = null;
                nextBeforeTimestamp = lastTimestamp;
                nextBeforeId = lastId;
                hasMoreRecords = canLoadMore;
                renderRecords(result, query, failure, append);
            });
        });
    }

    private void renderRecords(List<VaultRecord> records, String query, String error, boolean append) {
        if (!append) {
            recordList.removeAllViews();
            visibleRecords.clear();
            recordCardViews.clear();
            selectedRecordIds.clear();
            selectionMode = false;
            refreshSelectionToolbar();
        }
        if (loadMoreView != null && loadMoreView.getParent() == recordList) {
            recordList.removeView(loadMoreView);
        }
        if (error != null || records == null) {
            recordCount.setText("读取失败");
            addEmptyState("暂时无法读取记录", safe(error));
            return;
        }

        if (records.isEmpty() && !append) {
            recordCount.setText("0 条记录");
            addEmptyState(query.trim().isEmpty() ? "还没有留存消息" : "没有找到匹配内容",
                    query.trim().isEmpty() ? "开启通知监听权限后，符合条件的通知会显示在这里。" : "试试其他关键词或筛选条件。");
            return;
        }

        for (VaultRecord record : records) {
            visibleRecords.add(record);
            addRecordCard(record);
        }
        displayedRecordCount += records.size();
        recordCount.setText(displayedRecordCount + " 条" + (hasMoreRecords ? " · 还有更多" : " · 已全部显示"));

        if (hasMoreRecords) {
            if (loadMoreView == null) {
                loadMoreView = text("加载更多记录", 12, COLOR_BLUE, true);
                loadMoreView.setGravity(Gravity.CENTER);
                loadMoreView.setPadding(dp(12), dp(16), dp(12), dp(16));
                loadMoreView.setBackground(roundBackground(COLOR_SURFACE, dp(14), COLOR_LOAD_BORDER, dp(1)));
                loadMoreView.setFocusable(true);
                loadMoreView.setClickable(true);
                loadMoreView.setOnClickListener(view -> loadMoreRecords());
            }
            loadMoreView.setText("加载更多记录");
            loadMoreView.setEnabled(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(3);
            lp.bottomMargin = dp(12);
            recordList.addView(loadMoreView, lp);
        }
    }

    private void addRecordCard(VaultRecord record) {
        boolean revoke = VaultRecord.REVOKE.equals(record.type);
        boolean hidePreview = VaultPreferences.isPreviewHidden(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundBackground(COLOR_SURFACE, dp(16), COLOR_BORDER, dp(1)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            if (selectionMode) toggleRecordSelection(record.id);
            else showRecordDetails(record);
        });
        card.setOnLongClickListener(view -> {
            if (!selectionMode) enterSelectionMode(record.id);
            else toggleRecordSelection(record.id);
            return true;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView type = text(revoke ? "撤回提示 / 候选关联" : "普通消息", 10,
            revoke ? COLOR_RED : COLOR_GREEN, true);
        type.setPadding(dp(8), dp(5), dp(8), dp(5));
        type.setBackground(roundBackground(revoke ? COLOR_ERR_BG : COLOR_MESSAGE_BG, dp(30), Color.TRANSPARENT, 0));
        top.addView(type, new LinearLayout.LayoutParams(-2, -2));

        TextView favorite = text(record.favorite ? "★" : "☆", 18,
                record.favorite ? COLOR_BLUE : COLOR_MUTED, true);
        favorite.setGravity(Gravity.CENTER);
        favorite.setPadding(dp(8), dp(2), dp(8), dp(2));
        favorite.setContentDescription(record.favorite ? "取消收藏" : "收藏消息");
        favorite.setFocusable(true);
        favorite.setClickable(true);
        favorite.setOnClickListener(view -> toggleFavorite(record, favorite));
        LinearLayout.LayoutParams favoriteLp = new LinearLayout.LayoutParams(-2, -2);
        favoriteLp.leftMargin = dp(5);
        top.addView(favorite, favoriteLp);

        String time = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", record.timestamp).toString();
        TextView timestamp = text(time, 10, COLOR_MUTED, false);
        timestamp.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(0, -2, 1);
        timeLp.leftMargin = dp(6);
        top.addView(timestamp, timeLp);
        card.addView(top);

        TextView conversation = text(hidePreview ? "会话内容已遮挡" : safe(record.conversation),
                13, COLOR_TEXT, true);
        LinearLayout.LayoutParams conversationLp = new LinearLayout.LayoutParams(-1, -2);
        conversationLp.topMargin = dp(10);
        card.addView(conversation, conversationLp);

        if (!hidePreview && !safe(record.title).trim().isEmpty()) {
            TextView title = text(safe(record.title), 12, COLOR_MUTED, true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.topMargin = dp(5);
            card.addView(title, titleLp);
        }

        if (hidePreview && (!safe(record.title).trim().isEmpty() || !safe(record.text).trim().isEmpty())) {
            TextView hidden = text("消息预览已隐藏 · 点击查看详情", 12, COLOR_MUTED, false);
            LinearLayout.LayoutParams hiddenLp = new LinearLayout.LayoutParams(-1, -2);
            hiddenLp.topMargin = dp(6);
            card.addView(hidden, hiddenLp);
        } else if (!safe(record.text).trim().isEmpty()) {
            TextView body = text(safe(record.text), 13, COLOR_BODY, false);
            body.setLineSpacing(dp(2), 1f);
            body.setMaxLines(6);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
            bodyLp.topMargin = dp(6);
            card.addView(body, bodyLp);
        }

        if (!hidePreview && revoke && record.candidates != null && record.candidates.length() > 0) {
            TextView candidates = text("撤回前候选消息：" + record.candidates.length() + " 条 · 点击查看内容", 10, COLOR_RED, false);
            LinearLayout.LayoutParams candidateLp = new LinearLayout.LayoutParams(-1, -2);
            candidateLp.topMargin = dp(7);
            card.addView(candidates, candidateLp);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(9);
        recordList.addView(card, cardLp);
        recordCardViews.put(record.id, card);
        refreshRecordCardStyles();
    }

    private void showRecordDetails(VaultRecord record) {
        boolean revoke = VaultRecord.REVOKE.equals(record.type);
        TextView detail = text(buildRecordDetailText(record, null, revoke, false), 14, COLOR_TEXT, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(18), dp(14), dp(18), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(detail, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(revoke ? "撤回通知与候选消息" : "消息详情")
                .setView(scroll)
                .setNeutralButton("复制内容", null)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            String fullContent = detail.getText().toString();
            String preview = fullContent.length() > 500
                    ? fullContent.substring(0, 500) + "\n……（完整内容将在确认后复制）"
                    : fullContent;
            new AlertDialog.Builder(this)
                    .setTitle("确认复制消息")
                    .setMessage("确定将以下内容复制到系统剪贴板吗？\n\n" + preview)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认复制", (confirm, which) -> {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("NoticeVault message", fullContent));
                            toast("消息内容已复制");
                        }
                    })
                    .show();
        });

        if (!revoke || record.candidates == null || record.candidates.length() == 0) return;
        try {
            ioExecutor.execute(() -> {
                List<VaultRecord> candidates = Collections.emptyList();
                boolean failed = false;
                try (VaultDb db = new VaultDb(getApplicationContext())) {
                    candidates = db.findRevokeCandidates(record);
                } catch (Exception error) {
                    failed = true;
                }
                final List<VaultRecord> loaded = candidates;
                final boolean loadFailed = failed;
                mainHandler.post(() -> {
                    if (destroyed || !dialog.isShowing()) return;
                    detail.setText(buildRecordDetailText(record, loaded, false, loadFailed));
                });
            });
        } catch (RuntimeException rejected) {
            detail.setText(buildRecordDetailText(record, null, false, true));
        }
    }

    private String buildRecordDetailText(VaultRecord record, List<VaultRecord> candidates,
                                         boolean loading, boolean failed) {
        String time = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", record.timestamp).toString();
        StringBuilder content = new StringBuilder();
        content.append("时间：").append(time)
                .append("\n应用：").append(safe(record.packageName))
                .append("\n会话：").append(safe(record.conversation))
                .append("\n标题：").append(safe(record.title))
                .append("\n\n").append(safe(record.text));
        if (VaultRecord.REVOKE.equals(record.type)) {
            content.append("\n\n撤回候选说明\n")
                    .append("仅关联同一应用、同一会话、撤回通知前 5 分钟内的消息；")
                    .append("这些是时间与会话匹配结果，不代表微信确认的实际撤回对象。");
            if (loading) content.append("\n\n正在读取关联候选消息…");
            else if (failed) content.append("\n\n候选消息读取失败；原始留存记录未被修改。");
            else if (candidates == null || candidates.isEmpty()) content.append("\n\n没有找到仍存在且满足关联条件的候选消息。");
            else {
                for (int i = 0; i < candidates.size(); i++) {
                    VaultRecord candidate = candidates.get(i);
                    String candidateTime = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", candidate.timestamp).toString();
                    content.append("\n\n候选 ").append(i + 1).append(" · ").append(candidateTime);
                    if (!safe(candidate.title).trim().isEmpty()) content.append("\n标题：").append(safe(candidate.title));
                    content.append("\n内容：").append(safe(candidate.text).isEmpty() ? "（通知未提供正文）" : safe(candidate.text));
                }
            }
        }
        return content.toString();
    }

    private void addEmptyState(String title, String message) {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(28), dp(20), dp(28));
        empty.setBackground(roundBackground(COLOR_SURFACE, dp(17), COLOR_BORDER, dp(1)));
        TextView icon = text("⌕", 32, COLOR_EMPTY_ICON, true);
        icon.setGravity(Gravity.CENTER);
        empty.addView(icon);
        TextView heading = text(title, 14, COLOR_TEXT, true);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(8);
        empty.addView(heading, headingLp);
        TextView detail = text(message, 11, COLOR_MUTED, false);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(5);
        empty.addView(detail, detailLp);
        recordList.addView(empty);
    }

    private void passwordDialog(boolean export) {
        EditText input = new EditText(this);
        input.setHint("备份密码（至少 8 位）");
        input.setSingleLine(true);
        input.setInputType(129);
        int padding = dp(18);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(padding, dp(5), padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(export ? "设置备份密码" : "输入备份密码")
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续", null)
            .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            android.text.Editable editable = input.getText();
            char[] password = new char[editable.length()];
            editable.getChars(0, editable.length(), password, 0);
            editable.clear();
            if (password.length < 8) {
                java.util.Arrays.fill(password, (char) 0);
                toast("密码至少 8 位");
                return;
            }
            clearPendingPassword();
            pendingPassword = password;
            dialog.dismiss();
            Intent intent;
            if (export) {
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE, "NoticeVault-Backup.nvbk");
                startActivityForResult(intent, CREATE_BACKUP);
            } else {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, OPEN_BACKUP);
            }
        }));
        dialog.show();
    }

    private void clearPendingPassword() {
        if (pendingPassword != null) {
            java.util.Arrays.fill(pendingPassword, (char) 0);
            pendingPassword = null;
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_DEVICE_UNLOCK) {
            unlockRequestInProgress = false;
            if (result == RESULT_OK) {
                appUnlockedForSession = true;
                refreshMetrics();
                scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                return;
            }
            toast("身份验证未完成，已退出应用");
            finish();
            return;
        }
        if (request != CREATE_BACKUP && request != OPEN_BACKUP) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            clearPendingPassword();
            return;
        }
        if (backupRunning || pendingPassword == null) {
            clearPendingPassword();
            toast("备份任务正在运行或密码已失效，请重新操作");
            return;
        }

        final char[] taskPassword = pendingPassword.clone();
        clearPendingPassword();
        final Uri uri = data.getData();
        final boolean exporting = request == CREATE_BACKUP;
        final android.content.Context appContext = getApplicationContext();
        final android.content.ContentResolver resolver = getContentResolver();
        final String query = searchInput == null ? "" : searchInput.getText().toString();
        backupRunning = true;

        try {
            ioExecutor.execute(() -> {
                String message;
                byte[] plain = null;
                byte[] packed = null;
                VaultDb db = null;
                try {
                    db = new VaultDb(appContext);
                    if (exporting) {
                        JSONArray records = db.exportRecords();
                        if (records.length() > MAX_BACKUP_RECORDS) throw new IllegalArgumentException("备份记录超过数量限制");
                        JSONObject envelope = new JSONObject();
                        envelope.put("format", "NoticeVaultBackup");
                        envelope.put("version", BACKUP_SCHEMA_VERSION);
                        envelope.put("exportedAt", System.currentTimeMillis());
                        envelope.put("records", records);
                        plain = envelope.toString().getBytes(StandardCharsets.UTF_8);
                        if (plain.length > MAX_BACKUP_BYTES - 49) throw new IllegalArgumentException("备份数据超过 50 MB 限制");
                        packed = BackupCrypto.encrypt(plain, taskPassword);
                        try (OutputStream output = resolver.openOutputStream(uri)) {
                            if (output == null) throw new IllegalStateException("无法创建备份文件");
                            output.write(packed);
                            output.flush();
                        }
                        VaultPreferences.markBackupSucceeded(appContext, System.currentTimeMillis());
                        message = "加密备份导出成功（" + records.length() + " 条）";
                    } else {
                        try (InputStream stream = resolver.openInputStream(uri)) {
                            if (stream == null) throw new IllegalStateException("无法读取备份文件");
                            packed = readAllLimited(stream, MAX_BACKUP_BYTES);
                        }
                        plain = BackupCrypto.decrypt(packed, taskPassword);
                        String json = new String(plain, StandardCharsets.UTF_8).trim();
                        JSONArray records;
                        if (json.startsWith("[")) {
                            // Backward compatibility with the original 2.1.0/2.1.2 array payload.
                            records = new JSONArray(json);
                        } else {
                            JSONObject envelope = new JSONObject(json);
                            if (!"NoticeVaultBackup".equals(envelope.optString("format"))) {
                                throw new IllegalArgumentException("备份文件标识无效");
                            }
                            int schemaVersion = envelope.optInt("version", -1);
                            if (schemaVersion < 1 || schemaVersion > BACKUP_SCHEMA_VERSION) {
                                throw new IllegalArgumentException("备份版本不受支持");
                            }
                            records = envelope.optJSONArray("records");
                            if (records == null) throw new IllegalArgumentException("备份记录数据缺失");
                        }
                        if (records.length() > MAX_BACKUP_RECORDS) throw new IllegalArgumentException("备份记录超过 10000 条限制");
                        int[] importResult = db.importRecords(records);
                        message = "导入完成：新增 " + importResult[0] + " 条，跳过重复 " + importResult[1] + " 条";
                    }
                } catch (Exception error) {
                    if (error instanceof javax.crypto.AEADBadTagException) {
                        message = "备份操作失败：密码不正确，或备份文件已损坏/被修改";
                    } else if (error instanceof java.io.IOException) {
                        message = "备份操作失败：文件读取或写入失败";
                    } else {
                        String detail = error.getMessage();
                        message = "备份操作失败：" + (detail == null || detail.trim().isEmpty()
                                ? "文件格式无效或数据校验失败" : detail);
                    }
                } finally {
                    java.util.Arrays.fill(taskPassword, (char) 0);
                    if (plain != null) java.util.Arrays.fill(plain, (byte) 0);
                    if (packed != null) java.util.Arrays.fill(packed, (byte) 0);
                    if (db != null) db.close();
                }
                final String resultMessage = message;
                mainHandler.post(() -> {
                    if (destroyed) return;
                    backupRunning = false;
                    toast(resultMessage);
                    if (exporting) refreshBackupReminderBanner();
                    if (!exporting) scheduleSearch(query);
                });
            });
        } catch (RuntimeException error) {
            java.util.Arrays.fill(taskPassword, (char) 0);
            backupRunning = false;
            toast("无法启动备份任务，请稍后重试");
        }
    }

    private static byte[] readAllLimited(InputStream stream, int maxBytes) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                total += length;
                if (total > maxBytes) throw new java.io.IOException("备份文件超过大小限制");
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(MEDIUM_TYPEFACE);
        return view;
    }

    private GradientDrawable roundBackground(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private GradientDrawable gradientBackground(int[] colors, int radius) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backgroundAt > 0L) {
            if (System.currentTimeMillis() - backgroundAt > 60_000L) appUnlockedForSession = false;
            backgroundAt = 0L;
        }
        enforceAppLock();
        refreshStatus();
        refreshBackupReminderBanner();
        if (!VaultPreferences.isAppLockEnabled(this) || appUnlockedForSession) {
            refreshMetrics();
            if (searchInput != null) scheduleSearch(searchInput.getText().toString());
        }
    }

    @Override
    protected void onStop() {
        backgroundAt = System.currentTimeMillis();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        clearPendingPassword();
        searchGeneration++;
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        // Let queued I/O tasks finish so temporary backup passwords and buffers reach their finally blocks.
        ioExecutor.shutdown();
        if (activeSearchFuture != null) activeSearchFuture.cancel(true);
        searchExecutor.shutdownNow();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private static final class AppChoice {
        final String packageName;
        final String label;
        AppChoice(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    private static final class StatusTile {
        final LinearLayout container;
        final TextView value;
        StatusTile(LinearLayout container, TextView value) {
            this.container = container;
            this.value = value;
        }
    }
}