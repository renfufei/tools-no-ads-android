package com.tools.cnc.ui.qrcode;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tools.cnc.R;
import com.tools.cnc.databinding.DialogQrResultBinding;
import com.tools.cnc.util.UrlUtils;

/**
 * Shows a decoded QR result in a dialog: full copy, partial copy by selection,
 * and an "open in browser" action when the content is a web URL.
 */
public final class QrResultHelper {

    private QrResultHelper() {
    }

    /**
     * @param context   a UI context (Activity)
     * @param content   decoded QR content
     * @param onDismiss invoked when the dialog is dismissed (may be null)
     */
    public static void show(Context context, String content, Runnable onDismiss) {
        DialogQrResultBinding binding =
                DialogQrResultBinding.inflate(LayoutInflater.from(context));
        binding.tvContent.setText(content);

        if (UrlUtils.isWebUrl(content)) {
            binding.btnOpenBrowser.setVisibility(View.VISIBLE);
        }

        binding.btnCopyAll.setOnClickListener(v -> copy(context, content));

        binding.btnOpenBrowser.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(UrlUtils.normalize(content)));
            try {
                context.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(context, R.string.error, Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.qr_result_title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.confirm, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (onDismiss != null) {
                onDismiss.run();
            }
        });
        dialog.show();
    }

    private static void copy(Context context, String text) {
        ClipboardManager cm =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("QR", text));
        Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT).show();
    }
}
