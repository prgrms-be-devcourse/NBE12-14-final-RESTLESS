package com.restless.drop.application.dto;

import jakarta.validation.constraints.NotNull;

public record EnterDropQueueRequest(@NotNull Long variantId) { }
