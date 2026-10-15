package com.tools.cnc.ui.archive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.R;
import com.tools.cnc.databinding.FragmentArchiveBinding;
import com.tools.cnc.databinding.ItemModuleCardBinding;
import com.tools.cnc.util.PermissionHelper;

import java.util.Arrays;
import java.util.List;

/**
 * Entry screen for the archive module: lets the user choose between
 * compressing and decompressing before any file browsing happens.
 */
public class ArchiveFragment extends Fragment {

    private FragmentArchiveBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentArchiveBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        List<Option> options = Arrays.asList(
                new Option(getString(R.string.archive_compress),
                        getString(R.string.archive_compress_desc),
                        R.drawable.ic_archive,
                        R.id.action_archive_to_compressPicker,
                        FilePickerViewModel.MODE_COMPRESS),
                new Option(getString(R.string.archive_decompress),
                        getString(R.string.archive_decompress_desc),
                        R.drawable.ic_zip,
                        R.id.action_archive_to_decompressPicker,
                        FilePickerViewModel.MODE_DECOMPRESS)
        );

        binding.recyclerOptions.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerOptions.setAdapter(new OptionAdapter(options));

        if (!PermissionHelper.hasStoragePermission(requireContext())) {
            PermissionHelper.requestStoragePermission(requireActivity());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** A choose-your-function card. */
    private static class Option {
        final String name;
        final String description;
        final int iconRes;
        final int actionId;
        final String mode;

        Option(String name, String description, int iconRes, int actionId, String mode) {
            this.name = name;
            this.description = description;
            this.iconRes = iconRes;
            this.actionId = actionId;
            this.mode = mode;
        }
    }

    private static class OptionAdapter extends RecyclerView.Adapter<OptionAdapter.ViewHolder> {

        private final List<Option> options;

        OptionAdapter(List<Option> options) {
            this.options = options;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemModuleCardBinding itemBinding = ItemModuleCardBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Option option = options.get(position);
            holder.binding.ivIcon.setImageResource(option.iconRes);
            holder.binding.tvName.setText(option.name);
            holder.binding.tvDescription.setText(option.description);
            holder.itemView.setOnClickListener(v -> {
                Bundle args = new Bundle();
                args.putString(FilePickerFragment.ARG_MODE, option.mode);
                Navigation.findNavController(v).navigate(option.actionId, args);
            });
        }

        @Override
        public int getItemCount() {
            return options.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final ItemModuleCardBinding binding;

            ViewHolder(ItemModuleCardBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
