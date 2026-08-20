package com.sweet.authstudy.hr.position.application;

public final class PositionCommands {

    private PositionCommands() {
    }

    public record CreatePositionCommand(String code, String name, int level, int displayOrder) {
    }

    public record UpdatePositionCommand(String name, int level, int displayOrder, boolean active, long version) {
    }
}
