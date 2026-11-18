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
import android.widget.CheckBox;
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

    private int COLOR_BG = Color.rgb(244, 247, 251);
    private int COLOR_TEXT = Color.rgb(28, 42, 58);
    private int COLOR_MUTED = Color.rgb(106, 121, 139);
    private int COLOR_BLUE = Color.rgb(38, 105, 190);
    private int COLOR_GREEN = Color.rgb(26, 142, 100);
    private int COLOR_RED = Color.rgb(190, 65, 73);
    private boolean darkTheme;

    private LinearLayout recordList;
    private TextView recordCount;
    private EditText searchInput;
    private TextView notificationValue;
    private TextView shizukuServiceValue;
    private TextView shizukuPermissionValue;
    private TextView statusDetail;
    private TextView totalCountValue;
    private TextView todayCountValue;
    private LinearLayout filterRow;
    private LinearLayout advancedFilterRow;
    private LinearLayout selectionToolbar;
    private TextView manageRecordsView;
    private TextView loadMoreView;
    private final Set<Long> selectedRecordIds = new HashSet<>();
    private final List<VaultRecord> visibleRecords = new ArrayList<>();
    private boolean selectionMode;
    private int dateFilterDays;
    private long currentSinceTimestamp;
    private boolean favoriteOnlyFilter;
    private String currentPackageFilter = "";
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
        int systemNight = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean useDarkTheme = VaultPreferences.THEME_DARK.equals(themeMode)
                || (VaultPreferences.THEME_SYSTEM.equals(themeMode)
                && systemNight == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        setTheme(useDarkTheme ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        configureTheme();
        configureSystemBars();
        buildUi();
        refreshStatus();
    }

    private void configureTheme() {
        String mode = VaultPreferences.getThemeMode(this);
        int systemNight = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        darkTheme = VaultPreferences.THEME_DARK.equals(mode)
                || (VaultPreferences.THEME_SYSTEM.equals(mode)
                && systemNight == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        COLOR_BG = darkTheme ? 0xFF111820 : Color.rgb(244, 247, 251);
        COLOR_TEXT = darkTheme ? 0xFFE8EDF4 : Color.rgb(28, 42, 58);
        COLOR_MUTED = darkTheme ? 0xFFADB9C6 : Color.rgb(106, 121, 139);
        COLOR_BLUE = darkTheme ? 0xFF79B5F2 : Color.rgb(38, 105, 190);
        COLOR_GREEN = darkTheme ? 0xFF75D0A6 : Color.rgb(26, 142, 100);
        COLOR_RED = darkTheme ? 0xFFFF9299 : Color.rgb(190, 65, 73);
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(darkTheme ? 0xFF202A35 : Color.WHITE);
        int visibility = 0;
        if (!darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else if (!darkTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            visibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        window.getDecorView().setSystemUiVisibility(visibility);
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
        addSectionHeading(root, "权限与运行", "完成基础授权，让消息留存持续运行");
        addActionCard(root, "◉", "开启通知监听权限", "允许系统将可见通知交给本应用处理", COLOR_BLUE,
            this::openNotificationListenerSettings);
        addActionCard(root, "⌘", "检查 / 申请 Shizuku 权限", "无线调试启动 · 无需 Root", COLOR_GREEN, this::requestShizuku);
        addActionCard(root, "⚙", "电池优化设置", "检查系统后台限制，减少进程被停止的情况", COLOR_MUTED, this::openBatterySettings);

        addSectionHeading(root, "隐私与通知来源", "选择保存哪些应用的通知，并控制本地数据保留时间");
        addActionCard(root, "▣", "通知来源管理", "默认仅保存微信通知，可按需添加其他应用", COLOR_BLUE, this::showSourcePicker);
        addActionCard(root, "◷", "自动清理期限", "可关闭，或设置保留 7 / 30 / 90 天", COLOR_MUTED, this::showRetentionDialog);
        addActionCard(root, "▤", "设备锁保护", "使用手机系统锁屏密码或生物识别验证", COLOR_GREEN, this::toggleAppLock);

        addSectionHeading(root, "个性化与备份", "调整显示效果，定期保护本地数据");
        addActionCard(root, "◐", "界面主题", "跟随系统，或固定使用浅色 / 深色模式", COLOR_BLUE, this::showThemeDialog);
        addActionCard(root, "⇧", "导出加密备份", "将本机留存记录导出为 .nvbk 文件", COLOR_BLUE, () -> passwordDialog(true));
        addActionCard(root, "⇩", "导入加密备份", "从已有备份恢复记录", COLOR_GREEN, () -> passwordDialog(false));
        addActionCard(root, "✓", "检查本地数据完整性", "只读检查 SQLite 结构、密钥别名和存储占用", COLOR_GREEN, this::checkDatabaseHealth);
        addActionCard(root, "↻", "整理本地数据库", "回收空闲页面，尝试减少数据库文件占用", COLOR_MUTED, this::compactStorage);
        addDangerAction(root, "清空全部记录", "仅删除本机记录，不可撤销", this::confirmClearAll);

        buildRecordsSection(root);
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
        version.setText("VERSION 2.1.9");
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
        card.setBackground(roundBackground(Color.WHITE, dp(20), 0xFFE8EDF3, dp(1)));
        card.setElevation(dp(1));

        TextView heading = text("运行状态", 16, COLOR_TEXT, true);
        card.addView(heading);
        TextView hint = text("授权状态会在返回应用时自动刷新", 11, COLOR_MUTED, false);
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

    private void buildMetricsCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(roundBackground(Color.WHITE, dp(18), 0xFFE8EDF3, dp(1)));
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
        box.setBackground(roundBackground(0xFFF3F6FA, dp(13), 0xFFE9EEF4, dp(1)));

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
        card.setBackground(roundBackground(Color.WHITE, dp(17), 0xFFE8EDF3, dp(1)));
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

        TextView arrow = text("›", 25, 0xFF9AA8B7, false);
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
        card.setBackground(roundBackground(0xFFFFF8F8, dp(16), 0xFFF0D9DB, dp(1)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> action.run());
        card.addView(text(title, 14, COLOR_RED, true));
        TextView sub = text(subtitle, 10, 0xFF9B6A6E, false);
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
        manageRecordsView = text("管理", 12, COLOR_BLUE, true);
        manageRecordsView.setPadding(dp(10), dp(6), dp(4), dp(6));
        manageRecordsView.setOnClickListener(view -> setSelectionMode(!selectionMode));
        heading.addView(manageRecordsView);
        root.addView(heading);

        LinearLayout searchCard = new LinearLayout(this);
        searchCard.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.setGravity(Gravity.CENTER_VERTICAL);
        searchCard.setPadding(dp(13), 0, dp(13), 0);
        searchCard.setBackground(roundBackground(Color.WHITE, dp(15), 0xFFE3EAF1, dp(1)));
        TextView searchIcon = text("⌕", 22, COLOR_MUTED, true);
        searchIcon.setGravity(Gravity.CENTER);
        searchCard.addView(searchIcon, new LinearLayout.LayoutParams(dp(25), dp(50)));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(14);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setHintTextColor(0xFF9BA9B8);
        searchInput.setHint("搜索会话、标题或消息内容");
        searchInput.setPadding(dp(7), 0, dp(5), 0);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchCard.addView(searchInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
        searchLp.topMargin = dp(12);
        root.addView(searchCard, searchLp);

        TextView hint = text("按需加载，每次读取 30 条；点击消息卡片查看完整内容", 10, COLOR_MUTED, false);
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
        root.addView(filterRow, new LinearLayout.LayoutParams(-1, -2));

        advancedFilterRow = new LinearLayout(this);
        advancedFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        advancedFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        advancedFilterRow.setPadding(0, 0, 0, dp(8));
        addAdvancedFilterButton("时间：全部", this::showDateFilterDialog);
        addAdvancedFilterButton("来源：全部", this::showPackageFilterDialog);
        addAdvancedFilterButton("☆ 收藏", () -> {
            favoriteOnlyFilter = !favoriteOnlyFilter;
            updateAdvancedFilterLabels();
            scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
        });
        root.addView(advancedFilterRow, new LinearLayout.LayoutParams(-1, -2));
        updateAdvancedFilterLabels();

        selectionToolbar = new LinearLayout(this);
        selectionToolbar.setOrientation(LinearLayout.HORIZONTAL);
        selectionToolbar.setGravity(Gravity.CENTER_VERTICAL);
        selectionToolbar.setPadding(0, 0, 0, dp(8));
        addSelectionAction("全选已加载", this::selectAllLoaded);
        addSelectionAction("收藏", () -> applySelectionFavorite(true));
        addSelectionAction("取消收藏", () -> applySelectionFavorite(false));
        addSelectionAction("删除", this::confirmDeleteSelected);
        addSelectionAction("完成", () -> setSelectionMode(false));
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

    private void addAdvancedFilterButton(String label, Runnable action) {
        TextView chip = text(label, 10, COLOR_TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(7), dp(10), dp(7));
        chip.setBackground(roundBackground(0xFFF1F4F8, dp(24), 0xFFE1E7EE, dp(1)));
        chip.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(6);
        advancedFilterRow.addView(chip, lp);
    }

    private void addSelectionAction(String label, Runnable action) {
        TextView item = text(label, 10, COLOR_BLUE, true);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(7), dp(8), dp(7), dp(8));
        item.setBackground(roundBackground(0xFFF1F4F8, dp(10), 0xFFE1E7EE, dp(1)));
        item.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        selectionToolbar.addView(item, lp);
    }

    private void updateAdvancedFilterLabels() {
        if (advancedFilterRow == null || advancedFilterRow.getChildCount() < 3) return;
        String time = dateFilterDays == 0 ? "时间：全部"
                : dateFilterDays == 1 ? "时间：24小时"
                : "时间：" + dateFilterDays + "天";
        ((TextView) advancedFilterRow.getChildAt(0)).setText(time);
        String source = currentPackageFilter.isEmpty() ? "来源：全部"
                : "来源：" + applicationLabel(currentPackageFilter);
        ((TextView) advancedFilterRow.getChildAt(1)).setText(source);
        ((TextView) advancedFilterRow.getChildAt(2)).setText(favoriteOnlyFilter ? "★ 仅收藏" : "☆ 收藏");
        ((TextView) advancedFilterRow.getChildAt(2)).setTextColor(favoriteOnlyFilter ? 0xFFE2A400 : COLOR_TEXT);
    }

    private String applicationLabel(String packageName) {
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }

    private void showDateFilterDialog() {
        final int[] days = new int[]{0, 1, 7, 30};
        CharSequence[] labels = new CharSequence[]{"全部时间", "最近 24 小时", "最近 7 天", "最近 30 天"};
        int selected = 0;
        for (int i = 0; i < days.length; i++) if (days[i] == dateFilterDays) selected = i;
        new AlertDialog.Builder(this).setTitle("按时间筛选")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dateFilterDays = days[which];
                    updateAdvancedFilterLabels();
                    dialog.dismiss();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                }).setNegativeButton("取消", null).show();
    }

    private void showPackageFilterDialog() {
        List<String> packages = new ArrayList<>(VaultPreferences.getAllowedPackages(this));
        Collections.sort(packages);
        CharSequence[] labels = new CharSequence[packages.size() + 1];
        labels[0] = "全部来源";
        int selected = currentPackageFilter.isEmpty() ? 0 : -1;
        for (int i = 0; i < packages.size(); i++) {
            String packageName = packages.get(i);
            labels[i + 1] = applicationLabel(packageName);
            if (packageName.equals(currentPackageFilter)) selected = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("按通知来源筛选")
                .setSingleChoiceItems(labels, Math.max(0, selected), (dialog, which) -> {
                    currentPackageFilter = which == 0 ? "" : packages.get(which - 1);
                    updateAdvancedFilterLabels();
                    dialog.dismiss();
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                }).setNegativeButton("取消", null).show();
    }

    private void setSelectionMode(boolean enabled) {
        if (selectionMode == enabled) return;
        selectionMode = enabled;
        selectedRecordIds.clear();
        if (selectionToolbar != null) selectionToolbar.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (manageRecordsView != null) manageRecordsView.setText(enabled ? "完成" : "管理");
        scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
    }

    private void updateSelectionCount() {
        if (selectionMode && recordCount != null) {
            recordCount.setText(selectedRecordIds.size() + " 已选 · " + displayedRecordCount + " 已加载");
        }
    }

    private void rerenderVisibleRecords() {
        List<VaultRecord> snapshot = new ArrayList<>(visibleRecords);
        displayedRecordCount = 0;
        renderRecords(snapshot, currentQuery, null, false);
        updateSelectionCount();
    }

    private void selectAllLoaded() {
        for (VaultRecord record : visibleRecords) selectedRecordIds.add(record.id);
        rerenderVisibleRecords();
    }

    private long[] selectedIdsArray() {
        long[] ids = new long[selectedRecordIds.size()];
        int index = 0;
        for (Long id : selectedRecordIds) if (id != null && id > 0L) ids[index++] = id;
        return index == ids.length ? ids : java.util.Arrays.copyOf(ids, index);
    }

    private void applySelectionFavorite(boolean favorite) {
        long[] ids = selectedIdsArray();
        if (ids.length == 0) { toast("请先选择记录"); return; }
        ioExecutor.execute(() -> {
            int changed = 0;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                changed = db.setFavorites(ids, favorite);
            } catch (Exception error) {
                final String message = "批量更新收藏失败：" + safe(error.getMessage());
                mainHandler.post(() -> toast(message));
                return;
            }
            final int count = changed;
            mainHandler.post(() -> {
                if (destroyed) return;
                toast((favorite ? "已收藏 " : "已取消收藏 ") + count + " 条记录");
                setSelectionMode(false);
                refreshMetrics();
            });
        });
    }

    private void confirmDeleteSelected() {
        long[] ids = selectedIdsArray();
        if (ids.length == 0) { toast("请先选择记录"); return; }
        new AlertDialog.Builder(this).setTitle("删除所选记录")
                .setMessage("确定删除已选择的 " + ids.length + " 条记录？此操作不可撤销。建议先导出加密备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> ioExecutor.execute(() -> {
                    int deleted = 0;
                    try (VaultDb db = new VaultDb(getApplicationContext())) { deleted = db.deleteRecords(ids); }
                    catch (Exception error) {
                        final String message = "删除失败：" + safe(error.getMessage());
                        mainHandler.post(() -> toast(message));
                        return;
                    }
                    final int count = deleted;
                    mainHandler.post(() -> {
                        if (destroyed) return;
                        toast("已删除 " + count + " 条记录");
                        setSelectionMode(false);
                        refreshMetrics();
                    });
                })).show();
    }

    private void setRecordFavorite(VaultRecord record, boolean favorite) {
        if (record == null || record.id <= 0L) return;
        ioExecutor.execute(() -> {
            int changed = 0;
            try (VaultDb db = new VaultDb(getApplicationContext())) { changed = db.setFavorite(record.id, favorite); }
            catch (Exception error) {
                final String message = "更新收藏失败：" + safe(error.getMessage());
                mainHandler.post(() -> toast(message));
                return;
            }
            final boolean updated = changed > 0;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (updated) record.favorite = favorite;
                toast(favorite ? "已加入收藏" : "已取消收藏");
                scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
            });
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
        value.setBackground(roundBackground(0xFFF1F4F8, dp(30), 0xFFE4EAF0, dp(1)));
        value.setPadding(dp(7), dp(4), dp(7), dp(4));
    }

    private void updateStatusTile(TextView value, String label, boolean active) {
        value.setText(active ? "已开启" : "未开启");
        value.setTextColor(active ? COLOR_GREEN : COLOR_RED);
        value.setBackground(roundBackground(active ? 0xFFE9F7F0 : 0xFFFFEEEE, dp(30), Color.TRANSPARENT, 0));
        value.setPadding(dp(7), dp(4), dp(7), dp(4));
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
                    if (!VaultPreferences.WECHAT_PACKAGE.equals(currentPackageFilter)) {
                        currentPackageFilter = "";
                    }
                    updateAdvancedFilterLabels();
                    toast("已恢复为仅保存微信通知");
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    VaultPreferences.setAllowedPackages(this, selected);
                    if (!currentPackageFilter.isEmpty() && !selected.contains(currentPackageFilter)) {
                        currentPackageFilter = "";
                    }
                    updateAdvancedFilterLabels();
                    toast("通知来源设置已保存：" + selected.size() + " 个应用");
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                })
                .show();
    }

    private void showThemeDialog() {
        String current = VaultPreferences.getThemeMode(this);
        String[] modes = new String[]{VaultPreferences.THEME_SYSTEM, VaultPreferences.THEME_LIGHT, VaultPreferences.THEME_DARK};
        CharSequence[] labels = new CharSequence[]{"跟随系统", "浅色模式", "深色模式"};
        int selected = VaultPreferences.THEME_LIGHT.equals(current) ? 1
                : VaultPreferences.THEME_DARK.equals(current) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("界面主题")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    VaultPreferences.setThemeMode(this, modes[which]);
                    dialog.dismiss();
                    recreate();
                }).setNegativeButton("取消", null).show();
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

    private void addFilterChip(String label, String value) {
        TextView chip = text(label, 11, COLOR_MUTED, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(13), dp(8), dp(13), dp(8));
        chip.setBackground(roundBackground(0xFFF1F4F8, dp(30), 0xFFE1E7EE, dp(1)));
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
            chip.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
            chip.setBackground(roundBackground(
                    selected ? COLOR_BLUE : 0xFFF1F4F8, dp(30),
                    selected ? COLOR_BLUE : 0xFFE1E7EE, dp(1)));
        }
    }

    private void scheduleSearch(String query) {
        if (destroyed || recordList == null) return;
        currentQuery = query == null ? "" : query.trim();
        currentSinceTimestamp = RecordTimeFilter.sinceTimestamp(
                dateFilterDays, System.currentTimeMillis());
        selectedRecordIds.clear();
        visibleRecords.clear();
        updateSelectionCount();
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
            loadRecords(currentQuery, currentTypeFilter, 0L, 0L, generation, false);
        };
        mainHandler.postDelayed(pendingSearch, 220);
    }

    private void loadMoreRecords() {
        if (destroyed || searchLoading || !hasMoreRecords) return;
        loadRecords(currentQuery, currentTypeFilter, nextBeforeTimestamp, nextBeforeId,
                searchGeneration, true);
    }

    private void loadRecords(String query, String typeFilter, long beforeTimestamp,
                             long beforeId, int generation, boolean append) {
        if (searchLoading || generation != searchGeneration) return;
        searchLoading = true;
        if (loadMoreView != null && loadMoreView.getParent() == recordList) {
            loadMoreView.setText("正在加载…");
            loadMoreView.setEnabled(false);
        } else if (!append) {
            recordCount.setText("正在检索…");
        }

        final long sinceTimestamp = currentSinceTimestamp;
        final String packageFilter = currentPackageFilter;
        final boolean onlyFavorites = favoriteOnlyFilter;
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
                records = db.searchPage(query, typeFilter, sinceTimestamp, packageFilter,
                        onlyFavorites, beforeTimestamp, beforeId, PAGE_SIZE + 1);
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
        updateSelectionCount();

        if (hasMoreRecords) {
            if (loadMoreView == null) {
                loadMoreView = text("加载更多记录", 12, COLOR_BLUE, true);
                loadMoreView.setGravity(Gravity.CENTER);
                loadMoreView.setPadding(dp(12), dp(16), dp(12), dp(16));
                loadMoreView.setBackground(roundBackground(Color.WHITE, dp(14), 0xFFE2EAF2, dp(1)));
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
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundBackground(Color.WHITE, dp(16), 0xFFE7EDF3, dp(1)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            if (selectionMode) {
                if (selectedRecordIds.contains(record.id)) selectedRecordIds.remove(record.id);
                else selectedRecordIds.add(record.id);
                rerenderVisibleRecords();
            } else {
                showRecordDetails(record);
            }
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (selectionMode) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setTag(Long.valueOf(record.id));
            checkBox.setChecked(selectedRecordIds.contains(record.id));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedRecordIds.add(record.id);
                else selectedRecordIds.remove(record.id);
                updateSelectionCount();
            });
            top.addView(checkBox, new LinearLayout.LayoutParams(dp(34), dp(34)));
        }
        TextView favorite = text(record.favorite ? "★" : "☆", 18,
                record.favorite ? 0xFFE2A400 : COLOR_MUTED, true);
        favorite.setGravity(Gravity.CENTER);
        favorite.setPadding(dp(4), 0, dp(7), 0);
        favorite.setOnClickListener(view -> setRecordFavorite(record, !record.favorite));
        top.addView(favorite, new LinearLayout.LayoutParams(-2, dp(34)));
        TextView type = text(revoke ? "撤回提示 / 候选关联" : "普通消息", 10,
            revoke ? COLOR_RED : COLOR_GREEN, true);
        type.setPadding(dp(8), dp(5), dp(8), dp(5));
        type.setBackground(roundBackground(revoke ? 0xFFFFEEEE : 0xFFEAF7F0, dp(30), Color.TRANSPARENT, 0));
        top.addView(type, new LinearLayout.LayoutParams(-2, -2));

        String time = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", record.timestamp).toString();
        TextView timestamp = text(time, 10, COLOR_MUTED, false);
        timestamp.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(0, -2, 1);
        timeLp.leftMargin = dp(6);
        top.addView(timestamp, timeLp);
        card.addView(top);

        TextView conversation = text(safe(record.conversation), 13, COLOR_TEXT, true);
        LinearLayout.LayoutParams conversationLp = new LinearLayout.LayoutParams(-1, -2);
        conversationLp.topMargin = dp(10);
        card.addView(conversation, conversationLp);

        if (!safe(record.title).trim().isEmpty()) {
            TextView title = text(safe(record.title), 12, COLOR_MUTED, true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
            titleLp.topMargin = dp(5);
            card.addView(title, titleLp);
        }

        if (!safe(record.text).trim().isEmpty()) {
            TextView body = text(safe(record.text), 13, 0xFF44566B, false);
            body.setLineSpacing(dp(2), 1f);
            body.setMaxLines(6);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
            bodyLp.topMargin = dp(6);
            card.addView(body, bodyLp);
        }

        if (revoke && record.candidates != null && record.candidates.length() > 0) {
            TextView candidates = text("关联候选记录 ID：" + record.candidates, 10, COLOR_RED, false);
            LinearLayout.LayoutParams candidateLp = new LinearLayout.LayoutParams(-1, -2);
            candidateLp.topMargin = dp(7);
            card.addView(candidates, candidateLp);
        }

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(9);
        recordList.addView(card, cardLp);
    }

    private void showRecordDetails(VaultRecord record) {
        String time = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", record.timestamp).toString();
        String content = "时间：" + time + "\n应用：" + safe(record.packageName)
                + "\n会话：" + safe(record.conversation)
                + "\n标题：" + safe(record.title)
                + "\n\n" + safe(record.text);
        TextView detail = text(content, 14, COLOR_TEXT, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(18), dp(14), dp(18), dp(14));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(detail, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(VaultRecord.REVOKE.equals(record.type) ? "撤回通知详情" : "消息详情")
                .setView(scroll)
                .setNeutralButton("复制内容", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("NoticeVault message", content));
                        toast("消息内容已复制");
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    private void addEmptyState(String title, String message) {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(28), dp(20), dp(28));
        empty.setBackground(roundBackground(Color.WHITE, dp(17), 0xFFE8EDF3, dp(1)));
        TextView icon = text("⌕", 32, 0xFF9EADBD, true);
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
                        VaultPreferences.setLastBackupTime(appContext, System.currentTimeMillis());
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
                    if (!exporting) scheduleSearch(query);
                });
            });
        } catch (RuntimeException error) {
            java.util.Arrays.fill(taskPassword, (char) 0);
            backupRunning = false;
            toast("无法启动备份任务，请稍后重试");
        }
    }

    private void maybeShowBackupReminder() {
        if (destroyed || backupRunning || VaultPreferences.isAppLockEnabled(this) && !appUnlockedForSession) return;
        ioExecutor.execute(() -> {
            boolean shouldRemind = false;
            try (VaultDb db = new VaultDb(getApplicationContext())) {
                shouldRemind = VaultPreferences.shouldShowBackupReminder(
                        getApplicationContext(), System.currentTimeMillis(), db.count() > 0L);
            } catch (Exception ignored) {
                return;
            }
            if (!shouldRemind) return;
            mainHandler.post(() -> {
                if (destroyed || isFinishing() || backupRunning) return;
                VaultPreferences.markBackupReminderShown(MainActivity.this, System.currentTimeMillis());
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("重要消息备份提醒")
                        .setMessage("本机已有留存消息，但最近 7 天没有成功导出加密备份。建议定期保存 .nvbk 文件，并妥善保管备份密码。")
                        .setPositiveButton("现在备份", (dialog, which) -> passwordDialog(true))
                        .setNegativeButton("稍后提醒", null)
                        .show();
            });
        });
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
        view.setTextColor(resolveTextColor(color));
        if (bold) view.setTypeface(MEDIUM_TYPEFACE);
        return view;
    }

    private int resolveTextColor(int color) {
        if (!darkTheme) return color;
        if (color == Color.WHITE) return Color.WHITE;
        if (color == COLOR_TEXT) return 0xFFE8EDF4;
        if (color == COLOR_MUTED) return 0xFFADB9C6;
        if (color == 0xFF44566B) return 0xFFD0D9E2;
        if (color == 0xFF9B6A6E) return 0xFFFFB5BA;
        return color;
    }

    private int resolveSurfaceColor(int color) {
        if (!darkTheme) return color;
        if (color == Color.WHITE) return 0xFF202A35;
        if (color == COLOR_BG) return 0xFF111820;
        if (color == 0xFFE8EDF3 || color == 0xFFE3EAF1
                || color == 0xFFE7EDF3 || color == 0xFFE2EAF2
                || color == 0xFFE1E7EE || color == 0xFFE4EAF0) return 0xFF3A4653;
        if (color == 0xFFF3F6FA || color == 0xFFF1F4F8) return 0xFF293541;
        if (color == 0xFFFFEEEE) return 0xFF3A292D;
        if (color == 0xFFE9F7F0 || color == 0xFFEAF7F0) return 0xFF20382F;
        if (color == 0xFFE9EEF4) return 0xFF293541;
        if (color == 0xFFFFF8F8) return 0xFF33272B;
        return color;
    }

    private GradientDrawable roundBackground(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(resolveSurfaceColor(color));
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, resolveSurfaceColor(strokeColor));
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
        if (!VaultPreferences.isAppLockEnabled(this) || appUnlockedForSession) {
            refreshMetrics();
            if (searchInput != null) scheduleSearch(searchInput.getText().toString());
            maybeShowBackupReminder();
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