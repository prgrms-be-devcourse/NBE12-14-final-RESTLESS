package com.restless.concert.application.dto;

import java.util.List;

public record OrganizerLayoutInfo(Long layoutId, String name, String status, List<Section> sections) {
    public OrganizerLayoutInfo { sections = List.copyOf(sections); }
    public record Section(String sectionLabel, long sellableSeats) { }
}
