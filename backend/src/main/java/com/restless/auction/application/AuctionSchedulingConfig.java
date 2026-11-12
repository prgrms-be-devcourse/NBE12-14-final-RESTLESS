package com.restless.auction.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 경매 스케줄러를 켤 때만 Spring 스케줄링을 켠다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "restless.auction.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionSchedulingConfig {
}
