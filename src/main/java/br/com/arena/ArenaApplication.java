package br.com.arena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "br\\.com\\.arena\\.cli\\..*"
        )
)
@EnableScheduling
public class ArenaApplication {

	public static void main(String[] args) {
		SpringApplication.run(ArenaApplication.class, args);
	}

}
