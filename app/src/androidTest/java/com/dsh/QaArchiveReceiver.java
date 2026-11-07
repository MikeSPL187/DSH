package com.dsh;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Test APK's independent UID. Java-only so it needs no target APK runtime classes. */
public class QaArchiveReceiver extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean passed = false;
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null && "content".equals(uri.getScheme()) &&
                (getIntent().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                try (InputStream input = getContentResolver().openInputStream(uri);
                     ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if ("dsh_home/qa.txt".equals(entry.getName())) {
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            byte[] bytes = new byte[1024]; int n;
                            while ((n = zip.read(bytes)) != -1) output.write(bytes, 0, n);
                            passed = "backup-fixture".equals(new String(output.toByteArray(), StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        TextView result = new TextView(this);
        result.setText(passed ? "QA archive read PASS" : "QA archive read FAIL");
        result.setTextSize(20f);
        setContentView(result);
    }
}
