package com.study.tui.tea;

public record UpdateResult<M extends Model>(M model, Command command, boolean render) {
    public UpdateResult(M model, Command command) {
        this(model, command, true);
    }
}
