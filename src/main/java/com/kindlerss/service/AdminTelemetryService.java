package com.kindlerss.service;

import com.kindlerss.repository.TelemetryRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Aggregate usage reporting and administrator-managed user send controls. */
@Service
public class AdminTelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final UserSendLimitRepository limitRepository;
    private final UserRepository userRepository;

    public AdminTelemetryService(TelemetryRepository telemetryRepository,
                                 UserSendLimitRepository limitRepository,
                                 UserRepository userRepository) {
        this.telemetryRepository = telemetryRepository;
        this.limitRepository = limitRepository;
        this.userRepository = userRepository;
    }

    public TelemetryRepository.Summary summary() {
        return telemetryRepository.summary();
    }

    public java.util.List<TelemetryRepository.UserUsage> users() {
        return telemetryRepository.userUsage();
    }

    @Transactional
    public void updateLimit(long userId, Integer dailyLimit, Integer blockHours) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }
        if (dailyLimit != null && (dailyLimit < 1 || dailyLimit > 1_000)) {
            throw new IllegalArgumentException("Daily limit must be between 1 and 1000");
        }
        int hours = blockHours == null ? 0 : blockHours;
        if (hours < 0 || hours > 24 * 365) {
            throw new IllegalArgumentException("Block duration is invalid");
        }
        Instant blockedUntil = hours == 0 ? null : Instant.now().plus(hours, ChronoUnit.HOURS);
        if (dailyLimit == null && blockedUntil == null) {
            limitRepository.delete(userId);
        } else {
            limitRepository.save(userId, dailyLimit, blockedUntil);
        }
    }
}
