package com.dps.teknokent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TeknokentApplication {

	public static void main(String[] args) {
		SpringApplication.run(TeknokentApplication.class, args);
	}

}
