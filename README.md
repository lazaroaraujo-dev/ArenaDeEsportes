# 🏟️ Arena API

API REST desenvolvida em **Java** com **Spring Boot** para gerenciamento de uma arena esportiva, permitindo o cadastro de usuários, gerenciamento de quadras e realização de agendamentos.

O projeto foi desenvolvido seguindo boas práticas de arquitetura em camadas, facilitando a manutenção, escalabilidade e organização do código.

---

# 📌 Funcionalidades

* Cadastro de usuários
* Autenticação utilizando JWT
* Controle de perfis de usuário
* Cadastro de quadras
* Ativação e desativação de quadras
* Agendamento de horários
* Cancelamento de agendamentos
* Consulta de usuários
* Consulta de quadras
* Consulta de agendamentos
* Validação de regras de negócio
* Persistência de dados utilizando PostgreSQL

---

# 🛠 Tecnologias Utilizadas

* Java 21
* Spring Boot
* Spring Web
* Spring Data JPA
* Spring Security
* JWT (JSON Web Token)
* PostgreSQL
* Maven
* Lombok
* Hibernate
* Jakarta Validation

---

# 📂 Estrutura do Projeto

```
src
└── main
    └── java
        └── br.com.arena
            ├── auth
            ├── config
            ├── controller
            ├── dto
            ├── exception
            ├── mapper
            ├── model
            ├── repository
            ├── security
            ├── service
            └── ArenaApplication.java
```

---

# 🗄 Modelo de Dados

O sistema possui três entidades principais:

## 👤 Usuário

Responsável por acessar o sistema.

Informações:

* id
* nome
* email
* senha
* perfil

---

## 🏟 Quadra

Representa uma quadra disponível para reserva.

Informações:

* id
* nome
* tipo da quadra
* ativa

Tipos disponíveis:

* Futvôlei
* Beach Tennis
* Vôlei de Areia

---

## 📅 Agendamento

Relaciona um usuário a uma quadra em uma data e horário.

Informações:

* id
* usuário
* quadra
* data
* horário
* status

---

# 🔐 Segurança

A API utiliza autenticação baseada em **JWT**.

Após realizar o login, o usuário recebe um token que deverá ser enviado nas requisições protegidas através do cabeçalho:

```
Authorization: Bearer SEU_TOKEN
```

---

# ⚙️ Configuração

Configure o arquivo:

```
src/main/resources/application.properties
```

Exemplo:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/arena
spring.datasource.username=postgres
spring.datasource.password=sua_senha

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

---

# ▶️ Executando o Projeto

Clone o repositório:

```bash
git clone https://github.com/seu-usuario/arena-api.git
```

Entre na pasta:

```bash
cd arena-api
```

Execute:

```bash
./mvnw spring-boot:run
```

ou

```bash
mvn spring-boot:run
```

A aplicação ficará disponível em:

```
http://localhost:8080
```

---

# 📖 Arquitetura

O projeto utiliza arquitetura em camadas:

* **Controller** → Recebe as requisições HTTP.
* **Service** → Implementa as regras de negócio.
* **Repository** → Responsável pelo acesso ao banco de dados.
* **Model** → Representa as entidades do sistema.
* **DTO** → Transferência de dados.
* **Mapper** → Conversão entre entidades e DTOs.
* **Security** → Configuração da autenticação e autorização.

---

# 🚀 Melhorias Futuras

* Paginação nas consultas
* Upload de imagens das quadras
* Histórico de reservas
* Notificações por e-mail
* Dashboard administrativo
* Documentação automática com Swagger/OpenAPI
* Testes unitários e de integração

---

# 👨‍💻 Autor

Projeto desenvolvido como atividade acadêmica para estudo de:

* Java
* Spring Boot
* APIs REST
* Segurança com JWT
* Persistência de Dados com PostgreSQL
* Arquitetura em Camadas
