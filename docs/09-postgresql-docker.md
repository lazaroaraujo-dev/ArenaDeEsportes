# PostgreSQL local com Docker Compose

## Objetivo

Esta configuração executa **somente o PostgreSQL** em um contêiner para desenvolvimento local. A aplicação Spring Boot permanece fora do Docker e pode ser iniciada pelo IntelliJ conectando-se a `localhost:5432`. Nenhum contêiner ou volume foi iniciado, recriado ou removido durante esta etapa.

## Arquivos

- `docker-compose.yml`: descreve o serviço do banco, porta, volume e verificação de saúde.
- `.env.example`: modelo de variáveis locais, com senha fictícia que deve ser trocada.
- `.gitignore`: ignora `.env` e demais arquivos `.env.*`, mantendo `.env.example` versionável.

## Compose e imagem

O serviço Compose chama-se `postgres` e usa a imagem oficial `postgres:16`, alinhada à versão principal indicada no README. O Compose não define `container_name`, deixando o nome gerado pelo projeto e evitando colisões desnecessárias com contêineres existentes.

A porta é publicada como `127.0.0.1:5432:5432`: a aplicação e o IntelliJ no mesmo computador acessam o banco por `localhost:5432`, enquanto a porta não é publicada em todas as interfaces de rede do host.

## Variáveis de ambiente

Copie `.env.example` para `.env` e substitua `POSTGRES_PASSWORD` antes de iniciar o serviço:

```dotenv
POSTGRES_DB=arena
POSTGRES_USER=arena_dev
POSTGRES_PASSWORD=escolha-uma-senha-local
```

- `POSTGRES_DB`: banco inicial criado pela imagem; o padrão da configuração é `arena`.
- `POSTGRES_USER`: usuário inicial do PostgreSQL. É obrigatório no Compose.
- `POSTGRES_PASSWORD`: senha inicial desse usuário. É obrigatória no Compose e não deve ser um segredo real commitado.

O Docker Compose lê `.env` automaticamente para interpolar o arquivo Compose. `.env.example` é apenas um modelo e não é carregado automaticamente. A aplicação Spring não consome `POSTGRES_*` automaticamente; para iniciar o Spring Boot fora do Docker, configure também `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD` na configuração de execução.

**Importante:** a imagem oficial usa essas variáveis para inicializar um diretório de dados vazio. Alterar usuário, senha ou banco em `.env` depois da primeira inicialização não migra nem reconfigura automaticamente os dados já existentes.

## Volume persistente

O serviço monta o volume nomeado `arena_postgres_data` em `/var/lib/postgresql/data`. Os dados sobrevivem à parada e à remoção do contêiner. Esse nome foi escolhido após verificar que não existia; o volume existente `postgres_data` não é reutilizado.

Não execute `docker compose down -v` nem `docker volume rm` para esta configuração: essas opções podem apagar os dados persistidos. `docker compose stop postgres` e `docker compose down` (sem `-v`) preservam o volume.

## Iniciar e parar

No terminal, na raiz do projeto:

```bash
cp .env.example .env
```

No PowerShell, use `Copy-Item .env.example .env`.

Edite `.env`, troque a senha de exemplo e valide a configuração antes de iniciar:

```bash
docker compose config
docker compose up -d postgres
```

Para parar mantendo contêiner e dados:

```bash
docker compose stop postgres
```

Para remover o contêiner e a rede do Compose, preservando o volume de dados:

```bash
docker compose down
```

Não foi executado nenhum comando `up`, `stop` ou `down` durante esta etapa.

## Verificar funcionamento e saúde

```bash
docker compose ps
docker compose exec postgres pg_isready -U arena_dev -d arena
```

Substitua usuário e banco no comando se tiver usado valores diferentes no `.env`. O `docker compose ps` deve mostrar o serviço como `healthy` depois que o healthcheck passar. `pg_isready` confirma que o servidor está aceitando conexões; não substitui um teste de autenticação ou de acesso às tabelas da aplicação.

## Conectar pelo IntelliJ

Para o painel **Database** do IntelliJ, crie uma fonte de dados PostgreSQL com:

| Campo | Valor |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | valor de `POSTGRES_DB` (por padrão, `arena`) |
| User | valor de `POSTGRES_USER` (no exemplo, `arena_dev`) |
| Password | valor de `POSTGRES_PASSWORD` em `.env` |

Use a URL JDBC `jdbc:postgresql://localhost:5432/arena` e teste a conexão. Se o IntelliJ solicitar, baixe/configure o driver PostgreSQL pela própria IDE.

Para executar a API pelo IntelliJ, use esses mesmos dados na configuração de execução, por exemplo com `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arena`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. O projeto ainda exige `jwt.secret` e `jwt.expiration`, que não são definidos por esta configuração do banco e continuam precisando de configuração externa.

## Logs

Para consultar os últimos registros:

```bash
docker compose logs --tail=100 postgres
```

Para acompanhar os logs continuamente, use `docker compose logs -f postgres` e encerre o acompanhamento com `Ctrl+C` (isso não para o contêiner).

## Validações feitas nesta etapa

- Docker CLI: `Docker version 29.8.1`.
- Docker Compose: `Docker Compose version v5.5.1`.
- Daemon Docker: disponível (`ServerVersion 29.8.1`).
- Porta TCP `5432` no host: sem listener durante a verificação; `docker ps --filter publish=5432` não encontrou contêiner publicando essa porta.
- Contêineres PostgreSQL existentes: `postgres-dev` e `postgres-clinica` não publicam porta no host e usam volumes próprios (`infra-bancos_postgres_data` e `clinica_medica_postgres_data`). Não foram alterados.
- Volume `arena_postgres_data`: não existia na verificação; o volume existente `postgres_data` foi preservado e não será usado.
- `docker compose --env-file .env.example config --quiet`: concluiu com código 0.
- `POSTGRES_USER=arena_dev POSTGRES_PASSWORD=validation-placeholder docker compose config --quiet`: concluiu com código 0, validando `docker compose config` sem criar `.env`.
- `.env` está ignorado por `.gitignore`; `.env.example` não está ignorado.
- As validações de configuração não iniciam serviços nem criam volumes.

## Problemas conhecidos e conflitos de porta

- Se outro serviço passar a ocupar `localhost:5432`, o Docker não conseguirá publicar essa porta. Verifique processos/contêineres antes de iniciar. Se necessário, escolha outra porta no host alterando apenas o lado esquerdo do mapeamento, por exemplo `127.0.0.1:5433:5432`, e ajuste a URL JDBC e o IntelliJ para a nova porta.
- Não use uma porta ocupada nem remova volumes existentes para resolver o conflito; identifique o serviço antes.
- As variáveis de inicialização não redefinem uma base já criada no volume.
- O Compose desta etapa não inicia a aplicação e não substitui a configuração de datasource/JWT necessária para executar o Spring Boot.
- A suíte `contextLoads` anteriormente falhou sem URL de datasource no ambiente de teste; este Compose só fornece o serviço local quando alguém o iniciar e não altera essa configuração de teste.

## Roadmap

O item de Docker no README refere-se à conteinerização da aplicação e continua pendente. Esta etapa prepara apenas o banco; por isso nenhum item adicional do roadmap foi marcado como concluído.
