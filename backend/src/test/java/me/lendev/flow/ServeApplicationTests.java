package me.lendev.flow;

import me.lendev.flow.support.JdbcFixtureConfiguration;
import me.lendev.flow.support.SaTokenTestExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(JdbcFixtureConfiguration.class)
@ActiveProfiles({"h2", "test"})
@AutoConfigureMockMvc
@ExtendWith(SaTokenTestExtension.class)
public abstract class ServeApplicationTests {

}
