package com.tools.cnc.ui.archive;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tools.cnc.R;

import android.widget.TextView;

import java.io.File;

public class CompressOptionsDialogFragment extends DialogFragment {

    public interface OnOptionsConfirmedListener {
        void onConfirmed(File outputDir, String outputName, String password, int level);
    }

    private OnOptionsConfirmedListener listener;
    private File outputDir;
    private String defaultFileName;

    public static CompressOptionsDialogFragment newInstance(File outputDir, String defaultFileName) {
        CompressOptionsDialogFragment fragment = new CompressOptionsDialogFragment();
        Bundle args = new Bundle();
        args.putString("outputDir", outputDir.getAbsolutePath());
        args.putString("defaultFileName", defaultFileName);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnOptionsConfirmedListener(OnOptionsConfirmedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        outputDir = new File(args.getString("outputDir"));
        defaultFileName = args.getString("defaultFileName");

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_compress_options, null);

        TextView tvOutputDir = view.findViewById(R.id.tv_output_dir);
        TextInputEditText etOutputName = view.findViewById(R.id.et_output_name);
        SwitchMaterial switchPassword = view.findViewById(R.id.switch_password);
        TextInputLayout layoutPassword = view.findViewById(R.id.layout_password);
        TextInputEditText etPassword = view.findViewById(R.id.et_password);
        Slider sliderLevel = view.findViewById(R.id.slider_level);

        tvOutputDir.setText(outputDir.getAbsolutePath());
        etOutputName.setText(defaultFileName);

        switchPassword.setOnCheckedChangeListener((btn, checked) ->
                layoutPassword.setVisibility(checked ? View.VISIBLE : View.GONE));

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.compress_files_title)
                .setView(view)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    String name = etOutputName.getText() != null
                            ? etOutputName.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        name = defaultFileName;
                    }
                    if (!name.toLowerCase().endsWith(".zip")) {
                        name = name + ".zip";
                    }

                    String password = null;
                    if (switchPassword.isChecked() && etPassword.getText() != null) {
                        password = etPassword.getText().toString();
                        if (password.isEmpty()) {
                            password = null;
                        }
                    }

                    int level = (int) sliderLevel.getValue();

                    if (listener != null) {
                        listener.onConfirmed(outputDir, name, password, level);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
    }
}
