package com.kindlerss.service;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.TelemetryRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Aggregate usage reporting and administrator-managed user send controls. */
@Service
public class AdminTelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final UserSendLimitRepository limitRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    public AdminTelemetryService(TelemetryRepository telemetryRepository,
                                 UserSendLimitRepository limitRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 UserRepository userRepository) {
        this.telemetryRepository = telemetryRepository;
        this.limitRepository = limitRepository;
        this.subscriptionRepository = subscriptionRepository;
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

    /**
     * Grants or withdraws the paid plan by hand. This is the manual counterpart to
     * the provider callback, and it exists because callbacks go missing: a reader who
     * paid and was not credited needs a fix that does not involve a database client
     * at midnight. It is also how a complimentary subscription gets given out.
     *
     * @param months how long to grant for; zero or less puts the account back on free
     */
    @Transactional
    public void grantSupporter(long userId, int months) {
        if (userRepository.findById(userId).isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }
        if (months > 120) {
            throw new IllegalArgumentException("Grant at most 120 months at a time");
        }
        Subscription existing = subscriptionRepository.findByUserId(userId)
                .orElseGet(() -> Subscription.free(userId));
        if (months <= 0) {
            subscriptionRepository.save(new Subscription(userId, Plan.FREE, SubscriptionStatus.EXPIRED,
                    existing.interval(), existing.provider(), existing.providerCustomerId(),
                    existing.providerSubscriptionId(), Instant.now(), false,
                    existing.withdrawalConsentAt()));
            return;
        }
        Instant from = existing.currentPeriodEnd() != null && existing.currentPeriodEnd().isAfter(Instant.now())
                ? existing.currentPeriodEnd()
                : Instant.now();
        subscriptionRepository.save(new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.ACTIVE,
                existing.interval() == null ? BillingInterval.YEARLY : existing.interval(),
                existing.provider(), existing.providerCustomerId(), existing.providerSubscriptionId(),
                from.atZone(ZoneOffset.UTC).plusMonths(months).toInstant(), false,
                existing.withdrawalConsentAt()));
    }
}
