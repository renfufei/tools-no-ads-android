package com.tools.cnc.ui.qrcode;

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
import com.tools.cnc.databinding.FragmentQrToolsBinding;
import com.tools.cnc.databinding.ItemModuleCardBinding;
import com.tools.cnc.ui.home.ModuleItem;

import java.util.Arrays;
import java.util.List;

public class QrToolsFragment extends Fragment {

    private FragmentQrToolsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentQrToolsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        List<ModuleItem> tools = Arrays.asList(
                new ModuleItem(getString(R.string.qr_scan),
                        getString(R.string.qr_scan_desc),
                        R.drawable.ic_qr_scan, R.id.action_qrTools_to_scan),
                new ModuleItem(getString(R.string.qr_generate),
                        getString(R.string.qr_generate_desc),
                        R.drawable.ic_qr_create, R.id.action_qrTools_to_generate)
        );

        binding.recyclerTools.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerTools.setAdapter(new ToolAdapter(tools));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private static class ToolAdapter extends RecyclerView.Adapter<ToolAdapter.ViewHolder> {
        private final List<ModuleItem> items;

        ToolAdapter(List<ModuleItem> items) {
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
        public int getItemCount() {
            return items.size();
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
