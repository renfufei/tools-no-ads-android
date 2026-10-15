package com.tools.cnc.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.R;
import com.tools.cnc.databinding.FragmentHomeBinding;
import com.tools.cnc.databinding.ItemModuleCardBinding;

import java.util.Arrays;
import java.util.List;

public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        List<ModuleItem> modules = Arrays.asList(
                new ModuleItem(getString(R.string.module_archive),
                        getString(R.string.module_archive_desc),
                        R.drawable.ic_archive, R.id.action_home_to_archive),
                new ModuleItem(getString(R.string.module_image),
                        getString(R.string.module_image_desc),
                        R.drawable.ic_image, R.id.action_home_to_imageTools),
                new ModuleItem(getString(R.string.module_filetype),
                        getString(R.string.module_filetype_desc),
                        R.drawable.ic_file_type, R.id.action_home_to_fileType),
                new ModuleItem(getString(R.string.module_qrcode),
                        getString(R.string.module_qrcode_desc),
                        R.drawable.ic_qrcode, R.id.action_home_to_qrTools)
        );

        binding.recyclerModules.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.recyclerModules.setAdapter(new ModuleAdapter(modules));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private class ModuleAdapter extends RecyclerView.Adapter<ModuleAdapter.ViewHolder> {
        private final List<ModuleItem> items;

        ModuleAdapter(List<ModuleItem> items) {
            this.items = items;
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
            ModuleItem item = items.get(position);
            holder.binding.ivIcon.setImageResource(item.getIconRes());
            holder.binding.tvName.setText(item.getName());
            holder.binding.tvDescription.setText(item.getDescription());
            holder.itemView.setOnClickListener(v ->
                    Navigation.findNavController(v).navigate(item.getNavigationActionId()));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ItemModuleCardBinding binding;
            ViewHolder(ItemModuleCardBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
