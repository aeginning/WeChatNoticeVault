package com.aeginning.noticevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION = 210;
    private static final int CREATE_BACKUP = 301;
    private static final int OPEN_BACKUP = 302;
    private static final int MAX_VISIBLE_RECORDS = 100;
    private static final int MAX_BACKUP_RECORDS = 10000;
    private static final int MAX_BACKUP_BYTES = 50 * 1024 * 1024;

    private static final int COLOR_BG = Color.rgb(244, 247, 251);
    private static final int COLOR_TEXT = Color.rgb(28, 42, 58);
    private static final int COLOR_MUTED = Color.rgb(106, 121, 139);
    private static final int COLOR_BLUE = Color.rgb(38, 105, 190);
    private static final int COLOR_GREEN = Color.rgb(26, 142, 100);
    private static final int COLOR_RED = Color.rgb(190, 65, 73);

    private LinearLayout recordList;
    private TextView recordCount;
    private EditText searchInput;
    private TextView notificationValue;
    private TextView shizukuServiceValue;
    private TextView shizukuPermissionValue;
    private TextView statusDetail;
    private String shizukuMessage = "Shizuku 权限状态尚未检查";
    private char[] pendingPassword;
    private boolean backupRunning;
    private boolean destroyed;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
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
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        configureSystemBars();
        buildUi();
        refreshStatus();
        scheduleSearch("");
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
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
        addSectionHeading(root, "权限与运行", "完成基础授权，让消息留存持续运行");
        addActionCard(root, "◉", "开启通知监听权限", "允许系统将可见通知交给本应用处理", COLOR_BLUE,
            () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addActionCard(root, "⌘", "检查 / 申请 Shizuku 权限", "无线调试启动 · 无需 Root", COLOR_GREEN, this::requestShizuku);
        addActionCard(root, "⚙", "电池优化设置", "检查系统后台限制，减少进程被停止的情况", COLOR_MUTED, this::openBatterySettings);

        addSectionHeading(root, "备份与数据", "备份文件使用密码加密，请妥善保管密码");
        addActionCard(root, "⇧", "导出加密备份", "将本机留存记录导出为 .nvbk 文件", COLOR_BLUE, () -> passwordDialog(true));
        addActionCard(root, "⇩", "导入加密备份", "从已有备份恢复记录", COLOR_GREEN, () -> passwordDialog(false));
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
        title.setText("消息留存 PRO");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView version = new TextView(this);
        version.setText("VERSION 2.1.2");
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
        note.setText("消息内容保存在本机，不会自动上传到云端");
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

        TextView hint = text("搜索范围：最近 10,000 条留存记录", 10, COLOR_MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(6);
        hintLp.bottomMargin = dp(9);
        root.addView(hint, hintLp);

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
        boolean listenerEnabled = enabled != null && enabled.contains(getPackageName());

        updateStatusTile(notificationValue, "通知监听", listenerEnabled);
        updateStatusTile(shizukuServiceValue, "Shizuku 服务", shizukuAlive);
        updateStatusTile(shizukuPermissionValue, "Shizuku 授权", shizukuGranted);

        String detail = shizukuGranted ? "Shizuku 权限检查通过。" : shizukuMessage;
        if (!shizukuAlive) detail = "请先在 Shizuku 中启动无线调试服务，再申请授权。";
        statusDetail.setText(detail + "\n提示：Shizuku 无线调试权限不等于 Root，也不能读取微信私有数据库。 ");
    }

    private void updateStatusTile(TextView value, String label, boolean active) {
        value.setText(active ? "已开启" : "未开启");
        value.setTextColor(active ? COLOR_GREEN : COLOR_RED);
        value.setBackground(roundBackground(active ? 0xFFE9F7F0 : 0xFFFFEEEE, dp(30), Color.TRANSPARENT, 0));
        value.setPadding(dp(7), dp(4), dp(7), dp(4));
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

    private void clearAllRecords() {
        recordCount.setText("正在清空…");
        ioExecutor.execute(() -> {
            try {
                new VaultDb(getApplicationContext()).clearAll();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    toast("本机记录已清空");
                    scheduleSearch(searchInput == null ? "" : searchInput.getText().toString());
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (!destroyed) toast("清空失败：" + error.getMessage());
                });
            }
        });
    }

    private void scheduleSearch(String query) {
        if (destroyed || recordList == null) return;
        final int generation = ++searchGeneration;
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        pendingSearch = () -> loadRecords(query, generation);
        mainHandler.postDelayed(pendingSearch, 260);
    }

    private void loadRecords(String query, int generation) {
        recordCount.setText("正在检索…");
        ioExecutor.execute(() -> {
            List<VaultRecord> records = null;
            String errorMessage = null;
            try {
                records = new VaultDb(getApplicationContext()).search(query);
            } catch (Exception error) {
                errorMessage = error.getMessage();
            }
            final List<VaultRecord> result = records;
            final String failure = errorMessage;
            mainHandler.post(() -> {
                if (destroyed || generation != searchGeneration) return;
                renderRecords(result, query, failure);
            });
        });
    }

    private void renderRecords(List<VaultRecord> records, String query, String error) {
        recordList.removeAllViews();
        if (error != null || records == null) {
            recordCount.setText("读取失败");
            addEmptyState("暂时无法读取记录", safe(error));
            return;
        }

        int total = records.size();
        int visible = Math.min(total, MAX_VISIBLE_RECORDS);
        recordCount.setText(query.trim().isEmpty()
            ? total + " 条记录"
            : total + " 条匹配记录");

        if (total == 0) {
            addEmptyState(query.trim().isEmpty() ? "还没有留存消息" : "没有找到匹配内容",
                query.trim().isEmpty() ? "开启通知监听权限后，符合条件的通知会显示在这里。" : "试试其他会话名称或关键词。 ");
            return;
        }

        for (int i = 0; i < visible; i++) addRecordCard(records.get(i));
        if (total > visible) {
            TextView more = text("为保持浏览流畅，仅显示最近 " + visible + " 条；共匹配 " + total + " 条。", 11, COLOR_MUTED, false);
            more.setGravity(Gravity.CENTER);
            more.setPadding(dp(8), dp(14), dp(8), dp(12));
            recordList.addView(more);
        }
    }

    private void addRecordCard(VaultRecord record) {
        boolean revoke = VaultRecord.REVOKE.equals(record.type);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundBackground(Color.WHITE, dp(16), 0xFFE7EDF3, dp(1)));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
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
                        plain = records.toString().getBytes(StandardCharsets.UTF_8);
                        if (plain.length > MAX_BACKUP_BYTES - 49) throw new IllegalArgumentException("备份数据超过 50 MB 限制");
                        packed = BackupCrypto.encrypt(plain, taskPassword);
                        try (OutputStream output = resolver.openOutputStream(uri)) {
                            if (output == null) throw new IllegalStateException("无法创建备份文件");
                            output.write(packed);
                            output.flush();
                        }
                        message = "加密备份导出成功（" + records.length() + " 条）";
                    } else {
                        try (InputStream stream = resolver.openInputStream(uri)) {
                            if (stream == null) throw new IllegalStateException("无法读取备份文件");
                            packed = readAllLimited(stream, MAX_BACKUP_BYTES);
                        }
                        plain = BackupCrypto.decrypt(packed, taskPassword);
                        JSONArray records = new JSONArray(new String(plain, StandardCharsets.UTF_8));
                        if (records.length() > MAX_BACKUP_RECORDS) throw new IllegalArgumentException("备份记录超过 10000 条限制");
                        db.importRecords(records);
                        message = "导入完成：" + records.length() + " 条";
                    }
                } catch (Exception error) {
                    message = "备份操作失败：" + (error instanceof java.io.IOException ? "文件读取或写入失败" : "密码错误、文件损坏或数据格式不支持");
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
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        refreshStatus();
        if (searchInput != null) scheduleSearch(searchInput.getText().toString());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        clearPendingPassword();
        searchGeneration++;
        if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
        ioExecutor.shutdownNow();
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
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