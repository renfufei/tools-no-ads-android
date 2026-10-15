package com.tools.cnc.ui.home;

public class ModuleItem {
    private final String name;
    private final String description;
    private final int iconRes;
    private final int navigationActionId;

    public ModuleItem(String name, String description, int iconRes, int navigationActionId) {
        this.name = name;
        this.description = description;
        this.iconRes = iconRes;
        this.navigationActionId = navigationActionId;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getIconRes() { return iconRes; }
    public int getNavigationActionId() { return navigationActionId; }
}
