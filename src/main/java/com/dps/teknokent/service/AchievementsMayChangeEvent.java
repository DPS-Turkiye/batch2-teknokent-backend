package com.dps.teknokent.service;

import java.util.Set;
import java.util.UUID;

/** Something that can earn an achievement badge changed for these people: a session confirmed as held, or feedback. */
public record AchievementsMayChangeEvent(Set<UUID> userIds) {
}
