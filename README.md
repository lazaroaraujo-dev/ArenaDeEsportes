# 🏟️ ArenaDeEsportes API

> API RESTful para gerenciamento completo de arenas esportivas, controle de quadras de areia e sistema de agendamento em tempo real com prevenção de conflitos.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen?style=flat-square&logo=spring-boot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?style=flat-square&logo=postgresql)
![Spring Security](https://img.shields.io/badge/Security-JWT-red?style=flat-square&logo=springsecurity)
![Status](https://img.shields.io/badge/Status-Em_Desenvolvimento_(MVP_Ativo)-yellow?style=flat-square)

---

## 💻 Sobre o Projeto

O **ArenaDeEsportes** é uma solução de backend desenvolvida para automatizar a operação de centros esportivos (Beach Tennis, Futvôlei e Vôlei de Areia). 

A API resolve o problema clássico de *overbooking* (duplicidade de horários), valida datas no passado e gerencia permissões de acesso baseadas em papéis (*Role-Based Access Control*) com **Spring Security** e **JWT**.

---

## 🛠️ Tecnologias e Ferramentas

* **Linguagem:** Java 21 (LTS)
* **Framework:** Spring Boot (Spring Web, Spring Data JPA, Spring Validation)
* **Segurança:** Spring Security 6 + Auth0 Java JWT
* **Banco de Dados:** PostgreSQL & Hibernate ORM
* **Mapeamento & DTOs:** ModelMapper / MapStruct
* **Gerenciador de Dependências:** Apache Maven

---

## 📌 Principais Recursos

* **Segurança e RBAC:** Autenticação stateless via token JWT com diferenciação entre usuários comuns e administradores.
* **Gestão de Quadras:** Controle de disponibilidade, tipos de piso/esporte e ativação/desativação lógica.
* **Motor de Agendamento:** Validação inteligente que impede reservas com horários retroativos ou sobrepostos para a mesma quadra.
* **Tratamento Global de Erros:** Respostas padronizadas via `@ControllerAdvice` para validações e exceções de negócio.

---

## 🛣️ Endpoints Principais (Visão Geral)

| Método | Endpoint | Descrição | Acesso |
| :--- | :--- | :--- | :--- |
| `POST` | `/auth/register` | Registro de novos usuários | Público |
| `POST` | `/auth/login` | Autenticação e geração do token JWT | Público |
| `GET` | `/quadras` | Lista todas as quadras ativas | Autenticado |
| `POST` | `/quadras` | Cadastro de nova quadra | ADMIN |
| `POST` | `/agendamentos` | Reserva de quadra com validação de conflito | Autenticado |
| `DELETE` | `/agendamentos/{id}` | Cancelamento de agendamento existente | Autenticado |

---

## 📂 Arquitetura do Sistema

A API segue os padrões da **Arquitetura em Camadas** (*Layered Architecture*), priorizando desacoplamento e isolamento de regras de negócio:

```text
src/main/java/br/com/arena/
├── auth/           # Endpoints e fluxos de autenticação/login
├── config/         # Configurações globais (CORS, Beans)
├── controller/     # Camada REST (Exposição dos recursos HTTP)
├── dto/            # Data Transfer Objects com bean validation
├── exception/      # Tratamento centralizado de exceções
├── mapper/         # Conversores entre DTOs e Entidades
├── model/          # Entidades persistentes do JPA
├── repository/     # Interfaces de acesso a dados (Spring Data JPA)
├── security/       # Filtros JWT, SecurityFilterChain e UserDetails
└── service/        # Regras de negócio e validações
