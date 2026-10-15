package com.tools.cnc.ui.archive;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.List;

/**
 * Activity-scoped channel used to hand picked file paths from
 * {@link FilePickerFragment} over to {@link CompressFragment}
 * (and for "add more files" flows). Consumed once by the receiver.
 */
public class PickerResultViewModel extends ViewModel {

    private final MutableLiveData<List<String>> pickedPaths = new MutableLiveData<>();

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
}
