package app.tongxing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Tongxing {
    public static void main(String[] args) {
        SpringApplication.run(Tongxing.class, args);
    }
}
