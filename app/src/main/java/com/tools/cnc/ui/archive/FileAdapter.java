package com.tools.cnc.ui.archive;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.tools.cnc.R;
import com.tools.cnc.databinding.ItemFileBinding;
import com.tools.cnc.util.FileUtils;

/**
 * Row list for the file picker and archive browser.
 *
 * <p>Three selection affordances exist, and only the ones a mode actually needs are
 * shown:
 * <ul>
 *   <li>folder rows — a trailing "选择 / 已选" button, used when folders can be added
 *       to a compression list (never in decompress mode);</li>
 *   <li>file rows — a checkbox, used for multi-select compression;</li>
 *   <li>file rows — a trailing check mark, used for single-select modes such as
 *       decompression so the chosen archive is visibly marked.</li>
 * </ul>
 */
public class FileAdapter extends ListAdapter<FileItem, FileAdapter.ViewHolder> {

    public interface OnFileClickListener {
        /** Row body tapped on a folder: enter it. */
        void onFileClick(FileItem item);
    }

    public interface OnFolderPickListener {
        /** Trailing button tapped on a folder: select/deselect the folder. */
        void onFolderPick(FileItem item);
    }

    public interface OnSelectionToggleListener {
        void onSelectionToggle(FileItem item, boolean selected);
    }

    private boolean showFileCheckBox = false;
    private boolean folderPickEnabled = false;
    private boolean showSelectionMark = false;

    private OnFileClickListener clickListener;
    private OnFolderPickListener folderPickListener;
    private OnSelectionToggleListener selectionToggleListener;

    public FileAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<FileItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<FileItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull FileItem oldItem, @NonNull FileItem newItem) {
                    return oldItem.getFile().equals(newItem.getFile());
                }

                @Override
                public boolean areContentsTheSame(@NonNull FileItem oldItem, @NonNull FileItem newItem) {
                    return oldItem.equals(newItem) && oldItem.isSelected() == newItem.isSelected();
                }
            };

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnFolderPickListener(OnFolderPickListener listener) {
        this.folderPickListener = listener;
    }

    public void setOnSelectionToggleListener(OnSelectionToggleListener listener) {
        this.selectionToggleListener = listener;
    }

    /**
     * @param showFileCheckBox  show a checkbox on file rows (compress mode)
     * @param folderPickEnabled show the trailing select button on folder rows
     * @param showSelectionMark show a check mark on selected rows (single-select modes)
     */
    public void configureSelectionUi(boolean showFileCheckBox,
                                     boolean folderPickEnabled,
                                     boolean showSelectionMark) {
        this.showFileCheckBox = showFileCheckBox;
        this.folderPickEnabled = folderPickEnabled;
        this.showSelectionMark = showSelectionMark;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFileBinding binding = ItemFileBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemFileBinding binding;

        ViewHolder(ItemFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FileItem item) {
            binding.tvFileName.setText(item.getName());

            final boolean isFolder = item.getType() == FileItem.FileType.FOLDER;
            switch (item.getType()) {
                case FOLDER:
                    binding.ivFileIcon.setImageResource(R.drawable.ic_folder);
                    binding.tvFileInfo.setText(FileUtils.formatDate(item.getLastModified()));
                    break;
                case ZIP:
                case RAR:
                    binding.ivFileIcon.setImageResource(R.drawable.ic_zip);
                    binding.tvFileInfo.setText(FileUtils.formatFileSize(item.getSize()));
                    break;
                default:
                    binding.ivFileIcon.setImageResource(R.drawable.ic_file);
                    binding.tvFileInfo.setText(FileUtils.formatFileSize(item.getSize()));
                    break;
            }

            // Folder rows: explicit "select this folder" button, separate from entering it.
            boolean showPickButton = folderPickEnabled && isFolder;
            binding.btnPickFolder.setVisibility(showPickButton ? View.VISIBLE : View.GONE);
            if (showPickButton) {
                binding.btnPickFolder.setText(item.isSelected()
                        ? R.string.picker_selected : R.string.picker_select);
                binding.btnPickFolder.setIconResource(item.isSelected()
                        ? R.drawable.ic_check_circle : R.drawable.ic_add_circle);
                binding.btnPickFolder.setOnClickListener(v -> {
                    if (folderPickListener != null) {
                        folderPickListener.onFolderPick(item);
                    }
                });
            } else {
                binding.btnPickFolder.setOnClickListener(null);
            }

            // Check mark for single-select modes: folders are entered, never selected.
            boolean showMark = showSelectionMark && !isFolder && item.isSelected();
            binding.ivSelectedMark.setVisibility(showMark ? View.VISIBLE : View.GONE);

            boolean showCheckBox = showFileCheckBox && !isFolder;
            binding.cbSelect.setVisibility(showCheckBox ? View.VISIBLE : View.GONE);
            if (showCheckBox) {
                binding.cbSelect.setChecked(item.isSelected());
                binding.cbSelect.setOnClickListener(v -> {
                    if (selectionToggleListener != null) {
                        selectionToggleListener.onSelectionToggle(item,
                                binding.cbSelect.isChecked());
                    }
                });
            } else {
                binding.cbSelect.setOnClickListener(null);
            }

            itemView.setActivated(item.isSelected());
            itemView.setOnClickListener(v -> {
                if (isFolder) {
                    // Enter the folder.
                    if (clickListener != null) {
                        clickListener.onFileClick(item);
                    }
                } else if (selectionToggleListener != null) {
                    selectionToggleListener.onSelectionToggle(item, !item.isSelected());
                } else if (clickListener != null) {
                    clickListener.onFileClick(item);
                }
            });

            itemView.setOnLongClickListener(null);
        }
    }
}
