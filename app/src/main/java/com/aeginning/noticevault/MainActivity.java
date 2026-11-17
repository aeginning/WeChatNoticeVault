package com.aeginning.noticevault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSION=210, CREATE_BACKUP=301, OPEN_BACKUP=302;
    private static final String MIME="application/vnd.noticevault.backup";
    private LinearLayout list; private TextView status, count; private EditText search;
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(code,result)->refreshStatus();
    @Override public void onCreate(Bundle state){super.onCreate(state); Shizuku.addRequestPermissionResultListener(permissionListener); buildUi(); refreshStatus(); refreshRecords("");}
    private void buildUi(){
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,28,28,32); scroll.addView(root);
        TextView title=new TextView(this); title.setText("消息留存 PRO 2.1.0"); title.setTextSize(25); root.addView(title);
        TextView sub=new TextView(this); sub.setText("AES-GCM 加密留存 · 本地搜索 · 撤回候选 · 加密备份"); sub.setPadding(0,6,0,18); root.addView(sub);
        status=new TextView(this); status.setTextSize(14); root.addView(status);
        addButton(root,"开启通知监听权限",()->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        addButton(root,"检查 / 申请 Shizuku 权限",this::requestShizuku);
        addButton(root,"电池优化设置",()->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){toast("当前系统不支持该设置入口");}});
        addButton(root,"导出加密备份",()->passwordDialog(true)); addButton(root,"导入加密备份",()->passwordDialog(false));
        addButton(root,"清空全部记录",()->new AlertDialog.Builder(this).setMessage("确定删除本机全部留存记录？此操作不可撤销。") .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{new VaultDb(this).clearAll();refreshRecords("");}).show());
        count=new TextView(this); count.setPadding(0,20,0,8); root.addView(count);
        search=new EditText(this); search.setSingleLine(true); search.setHint("搜索会话、标题或消息内容"); root.addView(search);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){refreshRecords(s.toString());} public void afterTextChanged(Editable e){}});
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0,12,0,0); root.addView(list);
        setContentView(scroll);
    }
    private void addButton(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());root.addView(b);}
    private void requestShizuku(){try{if(!Shizuku.pingBinder()){toast("Shizuku 未运行，请先通过无线调试启动 Shizuku 服务");refreshStatus();return;} if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){toast("Shizuku 已授权");}else Shizuku.requestPermission(REQ_PERMISSION);refreshStatus();}catch(Throwable t){toast("Shizuku 状态检查失败："+t.getMessage());}}
    private void refreshStatus(){if(status==null)return;boolean alive=false,granted=false;try{alive=Shizuku.pingBinder();granted=alive&&Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;}catch(Throwable ignored){} String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");boolean listener=enabled!=null&&enabled.contains(getPackageName());status.setText("通知监听权限："+(listener?"已开启":"未开启")+"\nShizuku 服务："+(alive?"已连接":"未运行")+"\nShizuku 授权："+(granted?"已授权":"未授权")+"\n提示：Shizuku 无线调试权限不等于 Root，也不能读取微信私有数据。\n");}
    private void refreshRecords(String q){if(list==null)return;list.removeAllViews();try{List<VaultRecord> records=new VaultDb(this).search(q);count.setText("记录："+records.size()+" 条（最多显示最近 10000 条）");for(VaultRecord r:records){TextView item=new TextView(this);String label=(VaultRecord.REVOKE.equals(r.type)?"[撤回提示 / 候选关联] ":"[消息] ")+r.conversation+"\n"+r.title+"\n"+r.text+"\n"+android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",r.timestamp);if(VaultRecord.REVOKE.equals(r.type))label+="\n关联候选记录 ID："+r.candidates.toString();item.setText(label);item.setTextSize(15);item.setPadding(12,14,12,14);item.setBackgroundResource(android.R.drawable.dialog_holo_light_frame);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=10;list.addView(item,lp);}}catch(Exception e){count.setText("读取失败："+e.getMessage());}}
    private void passwordDialog(boolean export){EditText input=new EditText(this);input.setHint("备份密码（至少 8 位）");input.setInputType(129);new AlertDialog.Builder(this).setTitle(export?"设置备份密码":"输入备份密码").setView(input).setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->{String p=input.getText().toString();if(p.length()<8){toast("密码至少 8 位");return;} if(export){pendingPassword=p;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.putExtra(Intent.EXTRA_TITLE,"NoticeVault-Backup.nvbk");startActivityForResult(i,CREATE_BACKUP);}else{pendingPassword=p;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,OPEN_BACKUP);}}).show();}
    private String pendingPassword;
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{if(request==CREATE_BACKUP){JSONArray records=new VaultDb(this).exportRecords();byte[] plain=records.toString().getBytes(StandardCharsets.UTF_8);byte[] encrypted=BackupCrypto.encrypt(plain,pendingPassword.toCharArray());try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(encrypted);}toast("加密备份导出成功");}else if(request==OPEN_BACKUP){byte[] raw=readAll(getContentResolver().openInputStream(uri));byte[] plain=BackupCrypto.decrypt(raw,pendingPassword.toCharArray());JSONArray records=new JSONArray(new String(plain,StandardCharsets.UTF_8));new VaultDb(this).importRecords(records);toast("导入完成："+records.length()+" 条");refreshRecords(search.getText().toString());}}catch(Exception e){toast("备份操作失败：密码错误、文件损坏或格式不支持");}finally{pendingPassword=null;}}
    private byte[] readAll(InputStream in)throws Exception{try(InputStream input=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();refreshStatus();refreshRecords(search==null?"":search.getText().toString());}
    @Override protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(permissionListener);super.onDestroy();}
}
