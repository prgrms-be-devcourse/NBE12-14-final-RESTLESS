package com.restless.drop.application;

import com.restless.drop.infrastructure.DropQueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component("dropQueueAdmissionJob")
@RequiredArgsConstructor
public class DropQueueAdmissionJob {
    private final DropQueueEntryRepository entries;
    private final DropQueueService service;
    public void runOnce() {
        for (var dropId : entries.waitingDropIds()) service.admit(dropId);
    }
}
