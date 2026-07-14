package br.com.arena.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {

        return new OpenAPI()

                .info(
                        new Info()
                                .title("Arena API")
                                .version("1.0")
                                .description("""
                                        API para gerenciamento de agendamentos
                                        de quadras esportivas.
                                        """)
                                .contact(
                                        new Contact()
                                                .name("Lázaro")
                                )
                                .license(
                                        new License()
                                                .name("Uso Acadêmico")
                                )
                )

                .externalDocs(
                        new ExternalDocumentation()
                                .description("Projeto da disciplina de Padrões de Projetos")
                )


                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(
                        new io.swagger.v3.oas.models.Components()
                                .addSecuritySchemes("bearerAuth",
                                        new SecurityScheme()
                                                .name("bearerAuth")
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                );
    }
}
