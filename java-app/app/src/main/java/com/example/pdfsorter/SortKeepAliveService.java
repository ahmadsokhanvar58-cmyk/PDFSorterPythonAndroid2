package com.example.pdfsorter;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class SortKeepAliveService extends Service {
    public static final String ACTION_START =
            "com.example.pdfsorter.START";
    public static final String ACTION_DONE =
            "com.example.pdfsorter.DONE";
    public static final String ACTION_ERROR =
            "com.example.pdfsorter.ERROR";

    private static final String CHANNEL = "pdf_sorter_status";
    private static final int NOTIFICATION_ID = 1405;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "وضعیت مرتب‌سازی PDF",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(
                    "اعلان پیشرفت و پایان مرتب‌سازی فایل‌های PDF"
            );
            NotificationManager nm =
                    getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_DONE.equals(action)) {
            Uri pdf = intent.getData();
            int pages = intent.getIntExtra("pages", 0);
            String year = intent.getStringExtra("year");
            showFinished(pdf, pages, year);
            stopForeground(false);
            tryOpenPdf(pdf);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_ERROR.equals(action)) {
            String message = intent.getStringExtra("message");
            showError(message);
            stopForeground(false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(
                "در حال مرتب‌سازی PDFها",
                "برنامه را می‌توانی به پس‌زمینه ببری.",
                android.R.drawable.stat_sys_download,
                null,
                false
        ));
        return START_NOT_STICKY;
    }

    private Notification buildNotification(
            String title, String message, int icon,
            PendingIntent contentIntent, boolean finished) {

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(icon)
         .setContentTitle(title)
         .setContentText(message)
         .setStyle(new Notification.BigTextStyle().bigText(message))
         .setOnlyAlertOnce(!finished)
         .setAutoCancel(finished);

        if (contentIntent != null) {
            b.setContentIntent(contentIntent);
            if (finished && Build.VERSION.SDK_INT >= 16) {
                b.addAction(
                        android.R.drawable.ic_menu_view,
                        "بازکردن PDF",
                        contentIntent
                );
            }
        }
        return b.build();
    }

    private void showFinished(Uri pdf, int pages, String year) {
        PendingIntent open = null;

        if (pdf != null) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(pdf, "application/pdf");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            open = PendingIntent.getActivity(
                    this, 1406, view,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                    PendingIntent.FLAG_IMMUTABLE
            );
        }

        String message = "سال " + (year == null ? "" : year)
                + "؛ تعداد صفحات: " + pages
                + ". برای دیدن PDF لمس کن.";

        Notification n = buildNotification(
                "✅ مرتب‌سازی PDF تمام شد",
                message,
                android.R.drawable.stat_sys_download_done,
                open,
                true
        );

        NotificationManager nm =
                getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private void showError(String message) {
        Notification n = buildNotification(
                "❌ مرتب‌سازی PDF ناموفق بود",
                message == null ? "برای جزئیات برنامه را باز کن." : message,
                android.R.drawable.stat_notify_error,
                null,
                true
        );
        NotificationManager nm =
                getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private void tryOpenPdf(Uri pdf) {
        if (pdf == null) return;

        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(pdf, "application/pdf");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            // اگر اندروید بازکردن از پس‌زمینه را منع کند،
            // اعلان پایان کار همچنان برای بازکردن دستی باقی می‌ماند.
            Log.w("PDFSORT_TERM", "بازکردن خودکار PDF ممکن نشد", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
