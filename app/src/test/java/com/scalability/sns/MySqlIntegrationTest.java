package com.scalability.sns;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Boots the full app against a throwaway MySQL container with the schema applied. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/mysql/schema.sql,classpath:db/mysql/indexes.sql"
})
@Import(TestcontainersConfiguration.class)
public @interface MySqlIntegrationTest {
}
