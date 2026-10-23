package com.example.pdfsorter;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.content.Context;
import android.view.View;
import android.widget.*;
import android.graphics.Color;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {

    private static final int PICK_PDF = 1001;
    private final ArrayList<Uri> files = new ArrayList<>();

    private EditText yearEdit;
    private TextView status;
    private Button sortButton;

    private static final Set<String> BAD_CODES =
            new HashSet<>(Arrays.asList("95042683", "99990"));

    static class PageInfo {
        Uri uri;
        int page;
        String code;
        String clazz;
        int term;
        int original;

        PageInfo(Uri u, int p, String c, String cl, int t, int o) {
            uri = u;
            page = p;
            code = c;
            clazz = cl;
            term = t;
            original = o;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    3001
            );
        }

        PDFBoxResourceLoader.init(getApplicationContext());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("📚 مرتب‌ساز PDF سالانه");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        yearEdit = new EditText(this);
        yearEdit.setHint("سال تحصیلی، مثلاً 1405");
        yearEdit.setText("1405");
        yearEdit.setInputType(2);
        root.addView(yearEdit);

        Button choose = new Button(this);
        choose.setText("📂 انتخاب PDFها");
        root.addView(choose);

        sortButton = new Button(this);
        sortButton.setText("▶️ شروع مرتب‌سازی");
        root.addView(sortButton);

        status = new TextView(this);
        status.setText("هنوز فایلی انتخاب نشده است.");
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 0);
        root.addView(status);

        setContentView(root);

        choose.setOnClickListener(v -> pickFiles());
        sortButton.setOnClickListener(v -> startSort());
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_PDF || resultCode != RESULT_OK || data == null)
            return;

        files.clear();

        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();

            for (int i = 0; i < n; i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                files.add(u);

                try {
                    getContentResolver().takePersistableUriPermission(
                            u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception ignored) {}
            }
        } else if (data.getData() != null) {
            files.add(data.getData());
            try {
                getContentResolver().takePersistableUriPermission(
                        data.getData(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {}
        }

        status.setText("✅ " + files.size() + " فایل PDF انتخاب شد.");
    }

    private void startSort() {
        if (files.isEmpty()) {
            Toast.makeText(this,
                    "ابتدا PDFها را انتخاب کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        final String year = yearEdit.getText().toString().trim();

        if (year.length() < 4) {
            Toast.makeText(this,
                    "سال تحصیلی را وارد کن.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        sortButton.setEnabled(false);
        status.setText("⏳ در حال خواندن و مرتب‌سازی...");

        Intent keepAlive = new Intent(this, SortKeepAliveService.class);
        keepAlive.setAction(SortKeepAliveService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(keepAlive);
        } else {
            startService(keepAlive);
        }

        new Thread(() -> {
            try {
                ArrayList<PageInfo> pages = readPages();

                if (pages.isEmpty()) {
                    throw new IOException("هیچ صفحه‌ای از PDFهای انتخاب‌شده خوانده نشد.");
                }

                Collections.sort(pages, (a, b) -> {
                    int x;

                    // ترتیب قطعی: کد درس، کلاس، نوبت، ترتیب اصلی صفحه
                    x = compareNumeric(a.code, b.code);
                    if (x != 0) return x;

                    x = compareNumeric(a.clazz, b.clazz);
                    if (x != 0) return x;

                    // نوبت اول باید پیش از نوبت دوم همان کلاس باشد.
                    x = Integer.compare(a.term, b.term);
                    if (x != 0) return x;

                    return Integer.compare(a.original, b.original);
                });

                // گزارش تشخیصی ترتیب نهایی صفحات
                int missingCodes = 0;
                int missingClasses = 0;
                for (PageInfo item : pages) {
                    if ("999999999999".equals(item.code)) missingCodes++;
                    if ("999999".equals(item.clazz)) missingClasses++;
                }
                android.util.Log.i(
                    "PDFSORT_ORDER",
                    "TOTAL=" + pages.size()
                    + " MISSING_CODES=" + missingCodes
                    + " MISSING_CLASSES=" + missingClasses
                );
                for (int i = 0; i < Math.min(30, pages.size()); i++) {
                    PageInfo item = pages.get(i);
                    android.util.Log.i(
                        "PDFSORT_ORDER",
                        "index=" + (i + 1)
                        + " code=" + item.code
                        + " class=" + item.clazz
                        + " term=" + item.term
                        + " original=" + item.original
                    );
                }

                java.io.File temp = new java.io.File(
                        getCacheDir(),
                        "sorted_pdf_" + System.currentTimeMillis() + ".pdf"
                );
                Uri out = null;

                try {
                    // ابتدا PDF را در حافظه موقت بساز.
                    writeOutput(pages, temp);

                    // تا وقتی فایل موقت سالم نیست، خروجی نهایی نساز.
                    try (PDDocument check = PDDocument.load(temp, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
                        if (check.getNumberOfPages() != pages.size()) {
                            throw new IOException(
                                "بررسی فایل موقت ناموفق بود؛ تعداد صفحات نادرست است."
                            );
                        }
                    }

                    // فقط پس از موفقیت، فایل نهایی را ایجاد کن.
                    out = createOutput(year);

                    try (
                        InputStream src = new java.io.FileInputStream(temp);
                        OutputStream dst =
                            getContentResolver().openOutputStream(out, "w")
                    ) {
                        if (dst == null) {
                            throw new IOException(
                                "باز کردن فایل نهایی برای نوشتن ناموفق بود."
                            );
                        }

                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = src.read(buffer)) != -1) {
                            dst.write(buffer, 0, count);
                        }
                        dst.flush();
                    }

                    // خروجی نهایی را دوباره باز و بررسی کن.
                    try (InputStream verifyIn =
                             getContentResolver().openInputStream(out)) {
                        if (verifyIn == null) {
                            throw new IOException(
                                "بازبینی فایل خروجی ناموفق بود."
                            );
                        }

                        try (PDDocument verified = PDDocument.load(verifyIn, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
                            if (verified.getNumberOfPages() != pages.size()) {
                                throw new IOException(
                                    "تعداد صفحات خروجی نادرست است."
                                );
                            }
                        }
                    }

                } catch (Exception outputError) {
                    if (out != null) {
                        try {
                            getContentResolver().delete(out, null, null);
                        } catch (Exception ignored) {
                        }
                    }
                    throw outputError;
                } finally {
                    temp.delete();
                }

                Intent finished = new Intent(this, SortKeepAliveService.class);
                finished.setAction(SortKeepAliveService.ACTION_DONE);
                finished.setData(out);
                finished.putExtra("pages", pages.size());
                finished.putExtra("year", year);
                startService(finished);

                runOnUiThread(() -> {
                    status.setText(
                            "✅ مرتب‌سازی کامل شد.\n\n" +
                            "تعداد صفحات: " + pages.size() +
                            "\nسال: " + year +
                            "\n\n📄 خروجی در پوشه Downloads/مرتب‌ساز PDF قرار گرفت."
                    );

                    sortButton.setEnabled(true);
                });

            } catch (Exception e) {
            try {
                Intent failed = new Intent(this, SortKeepAliveService.class);
                failed.setAction(SortKeepAliveService.ACTION_ERROR);
                failed.putExtra(
                        "message",
                        e.getMessage() == null ? "خطای نامشخص" : e.getMessage()
                );
                startService(failed);
            } catch (Exception ignored) {}


                final String msg =
                        e.getClass().getSimpleName() +
                        ": " +
                        (e.getMessage() == null ? "" : e.getMessage());

                runOnUiThread(() -> {
                    status.setText("❌ خطا:\n" + msg);
                    sortButton.setEnabled(true);
                });
            }
        }).start();
    }

    
private String getPdfDisplayName(Uri uri) {
    String name = uri.getLastPathSegment();
    try (android.database.Cursor cursor = getContentResolver().query(
            uri, null, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) {
            int index = cursor.getColumnIndex(
                android.provider.OpenableColumns.DISPLAY_NAME);
            if (index >= 0) {
                String value = cursor.getString(index);
                if (value != null && !value.trim().isEmpty()) {
                    name = value;
                }
            }
        }
    } catch (Exception ignored) {
    }
    return name == null ? "فایل PDF" : name;
}

private ArrayList<PageInfo> readPages() throws Exception {

        ArrayList<PageInfo> result = new ArrayList<>();
        int original = 0;

        int fileIndex = 0;
    int totalFiles = files.size();

    for (Uri uri : files) {
        fileIndex++;
        final int currentFile = fileIndex;
        final String currentName = getPdfDisplayName(uri);
        runOnUiThread(() -> status.setText(
            "در حال خواندن فایل " + currentFile + " از " + totalFiles
            + "\\n" + currentName
            + "\\nدر حال استخراج متن صفحات..."));


            InputStream in = getContentResolver().openInputStream(uri);

            if (in == null) throw new IOException("باز کردن یکی از PDFهای انتخاب‌شده ناموفق بود: " + uri);

            PDDocument doc = null;

            try {
                doc = PDDocument.load(in, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());

                final String pageSeparator =
                        "<<<PDF_SORTER_PAGE_BOUNDARY_7F3A91C2>>>";

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setPageEnd(pageSeparator);
                stripper.setStartPage(1);
                stripper.setEndPage(doc.getNumberOfPages());

                String allText = stripper.getText(doc);
                String[] pageTexts =
                        allText.split(Pattern.quote(pageSeparator), -1);

                if (pageTexts.length < doc.getNumberOfPages()) {
                    throw new IOException(
                            "تشخیص مرز صفحات ناموفق بود؛ فایل: " + uri
                    );
                }

                for (int p = 0; p < doc.getNumberOfPages(); p++) {
                    String normalized = normalize(pageTexts[p]);

                    String code = extractCode(normalized);
                    String clazz = extractClass(normalized);
                    int term = extractTerm(normalized);

                    // گزارش موقت برای بررسی تشخیص نوبت صفحات
                    {
                        String debugText = normalized.replace('\n', ' ');
                        if (debugText.length() > 220) {
                            debugText = debugText.substring(0, 220);
                        }
                        android.util.Log.d(
                                "PDFSORT_TERM",
                                "page=" + (p + 1)
                                + " term=" + term
                                + " code=" + code
                                + " class=" + clazz
                                + " text=" + debugText
                        );
                    }

                    result.add(
                            new PageInfo(
                                    uri,
                                    p,
                                    code,
                                    clazz,
                                    term,
                                    original++
                            )
                    );
                }

            } finally {
                if (doc != null)
                    doc.close();

                in.close();
            }
        }

        return result;
    }

    private void writeOutput(
            ArrayList<PageInfo> pages,
            java.io.File outputFile
    ) throws Exception {

        java.util.Map<Uri, PDDocument> sources =
                new java.util.HashMap<>();
        PDDocument outDoc = new PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());

        try {
            int total = pages.size();
            if (total == 0) {
                throw new IOException("هیچ صفحه‌ای برای خروجی پیدا نشد.");
            }

            for (int i = 0; i < total; i++) {
                PageInfo info = pages.get(i);
                PDDocument source = sources.get(info.uri);

                if (source == null) {
                    try (InputStream in =
                            getContentResolver().openInputStream(info.uri)) {
                        if (in == null) {
                            throw new IOException("خواندن PDF ورودی ناموفق بود.");
                        }
                        source = PDDocument.load(in, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());
                    }
                    sources.put(info.uri, source);
                }

                PDPage sourcePage = source.getPage(info.page);
                PDPage imported = outDoc.importPage(sourcePage);
                imported.setMediaBox(sourcePage.getMediaBox());
                imported.setCropBox(sourcePage.getCropBox());
                imported.setRotation(sourcePage.getRotation());

                int done = i + 1;
                if (done % 5 == 0 || done == total) {
                    final int completed = done;
                    runOnUiThread(() -> status.setText(
                            "در حال ساخت PDF خروجی...\nصفحات پردازش‌شده: "
                                    + completed + " از " + total));
                }
            }

            try (OutputStream out =
                    new java.io.FileOutputStream(outputFile)) {
                outDoc.save(out);
            }
        } finally {
            for (PDDocument doc : sources.values()) {
                try {
                    doc.close();
                } catch (Exception ignored) {
                }
            }
            try {
                outDoc.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Uri createOutput(String year) {

        if (android.os.Build.VERSION.SDK_INT >= 29) {

            android.content.ContentValues values =
                    new android.content.ContentValues();

            values.put(
                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    "نمرات_مرتب_شده_" + year + ".pdf"
            );

            values.put(
                    android.provider.MediaStore.Downloads.MIME_TYPE,
                    "application/pdf"
            );

            values.put(
                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    "Download/مرتب‌ساز PDF/سال " + year + "/"
            );

            Uri u = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
            );

            if (u == null)
                throw new RuntimeException("ساخت فایل خروجی ناموفق بود.");

            return u;
        }

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.setType("application/pdf");
        i.putExtra(
                Intent.EXTRA_TITLE,
                "نمرات_مرتب_شده_" + year + ".pdf"
        );

        startActivityForResult(i, 2002);

        throw new RuntimeException(
                "در Android قدیمی باید محل ذخیره خروجی انتخاب شود."
        );
    }

    private static String normalize(String s) {
        if (s == null) return "";

        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != 0xFEFF &&
                Character.getType(c) != Character.FORMAT) {
                clean.append(c);
            }
        }

        return clean.toString()
            .replace('ي', 'ی').replace('ى', 'ی')
            .replace('ك', 'ک').replace('ۀ', 'ه').replace('ة', 'ه')
            .replace('٠','0').replace('١','1')
            .replace('٢','2').replace('٣','3')
            .replace('٤','4').replace('٥','5')
            .replace('٦','6').replace('٧','7')
            .replace('٨','8').replace('٩','9')
            .replace('۰','0').replace('۱','1')
            .replace('۲','2').replace('۳','3')
            .replace('۴','4').replace('۵','5')
            .replace('۶','6').replace('۷','7')
            .replace('۸','8').replace('۹','9')
            .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]", "")
            .replaceAll("[ \\t]+", " ");
    }

    private static String extractCode(String s) {
        if (s == null || s.isEmpty()) {
            return "999999999999";
        }

        String text = normalize(s);
        Matcher m = Pattern.compile(
                "[-–—]\\s*(\\d{4,6})(?!\\d)"
        ).matcher(text);

        while (m.find()) {
            String value = m.group(1);

            if (BAD_CODES.contains(value)) {
                continue;
            }

            // از اشتباه گرفتن 1405 انتهای سال تحصیلی 1404-1405 جلوگیری کن.
            if ("1405".equals(value)) {
                int start = m.start();
                String before = text.substring(Math.max(0, start - 12), start);
                if (before.matches("(?s).*1404\\s*")) {
                    continue;
                }
            }

            return value;
        }

        return "999999999999";
    }

    private static String extractClass(String s) {
        if (s == null || s.isEmpty()) return "999999";

        String text = normalize(s);
        Pattern[] patterns = new Pattern[] {
            Pattern.compile("(\\d{3})\\s+کالس\\s*[:：]", Pattern.DOTALL),
            Pattern.compile("(\\d{3})\\s+کلاس\\s*[:：]", Pattern.DOTALL)
        };

        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(text);
            if (m.find()) return m.group(1);
        }

        return "999999";
    }

    private static final Pattern TERM_ONE =
            Pattern.compile("نوبت\\s*اول");
    private static final Pattern TERM_TWO =
            Pattern.compile("نوبت\\s*دوم");

    private static final Pattern REPORT_TERM =
            Pattern.compile("برگ\\s*ریز\\s*نمرات\\s*دبیر[\\s\\S]*?نوبت\\s*(اول|دوم)");



    private static int extractTerm(String s) {
        if (s == null || s.isEmpty()) return 9;

        // ابتدا عنوان اصلی گزارش را بررسی کن؛
        // عنوان ستون «نوبت اول» نباید ملاک تشخیص باشد.
        String text = normalize(s);
        Matcher report = REPORT_TERM.matcher(text);
        if (report.find()) {
            return "اول".equals(report.group(1)) ? 1 : 2;
        }

        // اگر عنوان گزارش پیدا نشد، فقط عبارت یکتای نوبت را بپذیر.
        Matcher first = TERM_ONE.matcher(text);
        Matcher second = TERM_TWO.matcher(text);
        boolean hasFirst = first.find();
        boolean hasSecond = second.find();

        if (hasFirst && !hasSecond) return 1;
        if (hasSecond && !hasFirst) return 2;

        return 9;
    }

    private static int compareNumeric(String a, String b) {

        try {
            java.math.BigInteger x =
                    new java.math.BigInteger(a);

            java.math.BigInteger y =
                    new java.math.BigInteger(b);

            return x.compareTo(y);

        } catch (Exception e) {
            return a.compareTo(b);
        }
    }
}
