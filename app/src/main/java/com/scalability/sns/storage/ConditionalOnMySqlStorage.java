package com.scalability.sns.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** MySQL is the default storage (S1-S4); {@code sns.storage=cassandra} switches to S5. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = "sns.storage", havingValue = "mysql", matchIfMissing = true)
public @interface ConditionalOnMySqlStorage {
}
