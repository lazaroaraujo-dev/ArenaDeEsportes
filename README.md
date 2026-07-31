# 🏟 ArenaDeEsportes

API REST desenvolvida em **Java 21** utilizando **Spring Boot**, com o objetivo de gerenciar uma arena esportiva. O sistema permite o cadastro de usuários, gerenciamento de quadras e realização de agendamentos, contando com autenticação baseada em **JWT** e persistência de dados em **PostgreSQL**.

O projeto foi desenvolvido como atividade acadêmica para aplicação dos conceitos de desenvolvimento de APIs REST, arquitetura em camadas, autenticação e banco de dados.

---

# 🚀 Tecnologias Utilizadas

- Java 21
- Spring Boot
- Spring Web
- Spring Data JPA
- Spring Security
- JWT (JSON Web Token)
- PostgreSQL
- Hibernate
- Maven

---

# 📌 Funcionalidades

## Usuários

- Cadastro de usuários
- Login com autenticação JWT
- Diferentes perfis de acesso

## Quadras

- Cadastro de quadras
- Consulta de quadras
- Ativação e desativação de quadras
- Diferentes tipos de quadras

## Agendamentos

- Cadastro de agendamentos
- Consulta de agendamentos
- Cancelamento de reservas
- Validação para impedir agendamentos em datas passadas
- Validação para evitar conflitos de horários

---

# 📂 Estrutura do Projeto

```
src
├── main
│   └── java
│       └── br
│           └── com
│               └── arena
│                   ├── auth
│                   ├── config
│                   ├── controller
│                   ├── dto
│                   ├── exception
│                   ├── mapper
│                   ├── model
│                   ├── repository
│                   ├── security
│                   ├── service
│                   └── ArenaApplication.java
│
└── test
    └── java
        └── br
            └── com
                └── arena
```

---

# 🗄 Modelo de Dados

## 👤 Usuário

Representa os usuários do sistema.

Campos principais:

- id
- nome
- email
- senha
- perfil

---

## 🏟 Quadra

Representa uma quadra disponível para reserva.

Campos principais:

- id
- nome
- tipo
- ativa

Tipos disponíveis:

- Futvôlei
- Beach Tennis
- Vôlei de Areia

---

## 📅 Agendamento

Relaciona um usuário a uma quadra em determinada data e horário.

Campos principais:

- id
- usuário
- quadra
- data
- horário
- status

---

# 🔐 Autenticação

A API utiliza autenticação baseada em **JWT**.

Após realizar o login, o sistema retorna um token que deve ser enviado nas requisições protegidas.

Exemplo:

```
Authorization: Bearer SEU_TOKEN
```

---

# ⚙ Configuração

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

# ▶ Como executar

Clone o repositório

```bash
git clone https://github.com/lazaroaraujo-dev/ArenaDeEsportes.git
```

Entre na pasta

```bash
cd ArenaDeEsportes
```

Execute a aplicação

Linux/macOS

```bash
./mvnw spring-boot:run
```

Windows

```bash
mvn spring-boot:run
```

ou

```bash
.\mvnw.cmd spring-boot:run
```

A aplicação ficará disponível em:

```
http://localhost:8080
```

---

# 🏛 Arquitetura

O projeto segue uma arquitetura em camadas.

- **Controller** → Recebe as requisições HTTP.
- **Service** → Contém as regras de negócio.
- **Repository** → Responsável pelo acesso ao banco de dados.
- **Model** → Representa as entidades da aplicação.
- **DTO** → Objetos utilizados para transferência de dados.
- **Mapper** → Conversão entre entidades e DTOs.
- **Security** → Configuração da autenticação e autorização.

---

# 📈 Melhorias Futuras

- Testes unitários
- Testes de integração
- Documentação com Swagger/OpenAPI
- Paginação das consultas
- Upload de imagens das quadras
- Histórico de reservas
- Notificações por e-mail

---

# 👨‍💻 Autor

**Lázaro Araújo**

Projeto desenvolvido para fins acadêmicos, aplicando conceitos de:

- Java
- Spring Boot
- APIs REST
- Spring Security
- JWT
- PostgreSQL
- Arquitetura em Camadas
- Boas práticas de desenvolvimento
