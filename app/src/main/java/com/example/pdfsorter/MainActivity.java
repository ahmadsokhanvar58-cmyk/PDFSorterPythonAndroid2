package com.example.pdfsorter;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;

public class MainActivity extends Activity {
    private static final int PICK_PDFS = 7001;
    private final ArrayList<Uri> selected = new ArrayList<>();
    private EditText year;
    private TextView status, filesLabel;
    private Button start;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(28,32,28,24);
        TextView title = new TextView(this); title.setText("📚 مرتب‌ساز PDF سالانه"); title.setTextSize(23); title.setPadding(0,0,0,24); root.addView(title);
        year = new EditText(this); year.setSingleLine(true); year.setHint("سال تحصیلی، مثلاً 1405"); year.setText("1405"); root.addView(year);
        Button pick = new Button(this); pick.setText("📂 انتخاب PDFها"); root.addView(pick);
        filesLabel = new TextView(this); filesLabel.setText("هنوز فایلی انتخاب نشده است"); filesLabel.setPadding(0,12,0,12); root.addView(filesLabel);
        start = new Button(this); start.setText("▶️ شروع مرتب‌سازی"); root.addView(start);
        status = new TextView(this); status.setText("فایل‌های اصلی بدون تغییر می‌مانند."); status.setPadding(0,20,0,0); root.addView(status);
        setContentView(root);
        pick.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/pdf"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,PICK_PDFS); });
        start.setOnClickListener(v -> runSort());
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_PDFS || resultCode!=RESULT_OK || data==null) return;
        selected.clear();
        if(data.getClipData()!=null) { for(int n=0;n<data.getClipData().getItemCount();n++) selected.add(data.getClipData().getItemAt(n).getUri()); }
        else if(data.getData()!=null) selected.add(data.getData());
        filesLabel.setText("تعداد فایل انتخاب‌شده: "+selected.size());
    }
    private void runSort() {
        if(selected.isEmpty()){ status.setText("ابتدا PDFها را انتخاب کنید."); return; }
        String y=year.getText().toString().trim(); if(y.isEmpty()){status.setText("سال تحصیلی را وارد کنید.");return;}
        start.setEnabled(false); status.setText("در حال آماده‌سازی فایل‌ها...");
        new Thread(() -> {
            try {
                File inputDir = new File(getFilesDir(),"input"); if(!inputDir.exists()) inputDir.mkdirs();
                ArrayList<String> paths = new ArrayList<>();
                for(int n=0;n<selected.size();n++) {
                    Uri uri=selected.get(n); File dst=new File(inputDir,String.format(Locale.US,"input_%04d.pdf",n));
                    try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(dst)) { if(in==null) throw new IOException("بازکردن PDF ناموفق بود"); byte[] buf=new byte[65536]; int k; while((k=in.read(buf))!=-1) out.write(buf,0,k); }
                    paths.add(dst.getAbsolutePath());
                }
                File outDir=new File(getExternalFilesDir(null),"مرتب‌ساز PDF/سال "+y); if(!outDir.exists()) outDir.mkdirs();
                File out=new File(outDir,"نمرات_مرتب_شده_"+y+".pdf");
                PyObject py=Python.getInstance().getModule("sorter");
                PyObject result=py.callAttr("sort_pdfs",paths,out.getAbsolutePath());
                runOnUiThread(() -> { status.setText(result.toString()+"\n"+out.getAbsolutePath()); start.setEnabled(true); });
            } catch(Exception e) { runOnUiThread(() -> { status.setText("خطا: "+e.getMessage()); start.setEnabled(true); }); }
        }).start();
    }
}
