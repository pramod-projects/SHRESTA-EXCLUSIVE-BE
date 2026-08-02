package com.shrestaexclusive.platform.mutation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shresta.mutation")
public class MutationSafetyProperties {

    private Idempotency idempotency = new Idempotency();
    private Locking locking = new Locking();

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    public Locking getLocking() {
        return locking;
    }

    public void setLocking(Locking locking) {
        this.locking = locking;
    }

    public static class Idempotency {
        private boolean enabled = true;
        private boolean requireKey = true;
        private Duration ttl = Duration.ofHours(24);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequireKey() {
            return requireKey;
        }

        public void setRequireKey(boolean requireKey) {
            this.requireKey = requireKey;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class Locking {
        private boolean enabled = true;
        private Duration ttl = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }
}
