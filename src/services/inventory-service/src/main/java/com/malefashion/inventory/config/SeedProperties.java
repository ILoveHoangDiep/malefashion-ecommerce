package com.malefashion.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional startup seed. Disabled by default; the local profile enables it.
 */
@ConfigurationProperties(prefix = "inventory.seed")
public record SeedProperties(
        boolean enabled,
        String path
) {
}
