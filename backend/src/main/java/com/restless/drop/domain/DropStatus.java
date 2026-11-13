package com.restless.drop.domain;

public enum DropStatus {
    DRAFT, SCHEDULED, OPEN, CLOSED, CANCELLED;

    public boolean isPublished() {
        return this == SCHEDULED || this == OPEN || this == CLOSED;
    }
}
