package com.tools.cnc.ui.archive;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tools.cnc.R;
import com.tools.cnc.databinding.DialogErrorMessageBinding;

/**
 * Failure report dialog: the full error text is shown in a scrollable, selectable box
 * instead of a toast that disappears before it can be read.
 *
 * <p>Every failure path in the archive module routes through here, so the detail
 * (exception type, entry name, target path, hints) actually reaches the user.
 */
public class ErrorDialogFragment extends DialogFragment {

    public static final String TAG = "error_dialog";

    private static final String ARG_TITLE = "arg_error_title";
    private static final String ARG_MESSAGE = "arg_error_message";

    private DialogErrorMessageBinding binding;

    public static ErrorDialogFragment newInstance(@Nullable String title, @Nullable String message) {
        ErrorDialogFragment fragment = new ErrorDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_MESSAGE, message);
        fragment.setArguments(args);
        return fragment;
    }

    /** Shows the dialog with the default "操作失败" title. Safe to call when detached. */
    public static void showError(@Nullable FragmentManager manager, @Nullable String message) {
        showError(manager, null, message);
    }

    /**
     * Shows the dialog, skipping the request when the fragment manager is gone or an
     * error dialog is already on screen (a second failure must not stack dialogs).
     */
    public static void showError(@Nullable FragmentManager manager,
                                 @Nullable String title,
                                 @Nullable String message) {
        if (manager == null || manager.isDestroyed()) {
            return;
        }
        if (manager.findFragmentByTag(TAG) != null) {
            return;
        }
        if (message == null || message.trim().isEmpty()) {
            message = "未知错误（没有可用的错误信息）";
        }
        newInstance(title, message).show(manager, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogErrorMessageBinding.inflate(getLayoutInflater());

        Bundle args = getArguments();
        String title = args == null ? null : args.getString(ARG_TITLE);
        String message = args == null ? null : args.getString(ARG_MESSAGE);
        binding.tvErrorMessage.setText(message);

        final String copyText = message;
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title != null ? title : getString(R.string.error))
                .setView(binding.getRoot())
                .setPositiveButton(R.string.error_ok, null)
                .setNeutralButton(R.string.error_copy, (dialog, which) -> copy(copyText))
                .create();
    }

    private void copy(String text) {
        Context context = getContext();
        if (context == null || text == null) {
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("error", text));
            Toast.makeText(context, R.string.error_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
