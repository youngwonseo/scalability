package com.scalability.sns;

import org.springframework.boot.SpringApplication;

public class TestSnsApplication {

	public static void main(String[] args) {
		SpringApplication.from(SnsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
