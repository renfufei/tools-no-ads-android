package com.tools.cnc.ui.archive;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

/**
 * Activity-scoped channel used to hand results back from {@link FilePickerFragment}:
 * <ul>
 *   <li>{@link #getPickedPaths()} — files/folders selected for compression (also used by
 *       the "add more files" flow);</li>
 *   <li>{@link #getPickedDirectory()} — the folder chosen as the compression output
 *       directory.</li>
 * </ul>
 * Each result is consumed once by its receiver.
 */
public class PickerResultViewModel extends ViewModel {

    private final MutableLiveData<List<String>> pickedPaths = new MutableLiveData<>();
    private final MutableLiveData<String> pickedDirectory = new MutableLiveData<>();

    public LiveData<List<String>> getPickedPaths() {
        return pickedPaths;
    }

    public void setPickedPaths(List<String> paths) {
        pickedPaths.setValue(paths);
    }

    /** Clears the pending result after the receiver has consumed it. */
    public void consume() {
        pickedPaths.setValue(null);
    }

    public LiveData<String> getPickedDirectory() {
        return pickedDirectory;
    }

    public void setPickedDirectory(String path) {
        pickedDirectory.setValue(path);
    }

    /** Clears the pending directory result after the receiver has consumed it. */
    public void consumeDirectory() {
        pickedDirectory.setValue(null);
    }
}
