package org.snomed.valuesets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ValueSetsApplication {

	public static void main(String[] args) {
		SpringApplication.run(ValueSetsApplication.class, args);
	}
}
