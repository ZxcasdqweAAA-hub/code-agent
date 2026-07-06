package com.study.tui.tea;

public interface Model {
    Command init();

    UpdateResult<? extends Model> update(Message msg);

    String view();

    default String dumpHistory() {
        return "";
    }
}
