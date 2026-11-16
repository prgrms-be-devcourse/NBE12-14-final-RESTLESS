package com.restless.drop.application.dto;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import org.springframework.data.domain.Sort;

public enum DropListSort {
    OPENS_AT, NEWEST;

    public static DropListSort parse(String value) {
        if (value == null) return OPENS_AT;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    public Sort order() {
        return this == NEWEST
                ? Sort.by(Sort.Direction.DESC, "createdAt", "id")
                : Sort.by(Sort.Direction.ASC, "opensAt", "id");
    }
}
