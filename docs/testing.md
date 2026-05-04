# Estratégia de Testes — OrderFlow

## Filosofia

Testes não são uma camada cosmética — são parte estrutural do design. Código bem testado é, quase universalmente, código bem desenhado: porque desacoplado, porque tem responsabilidades claras, porque expõe interfaces sãs. O OrderFlow trata testes como artefato de primeira classe, com investimento equivalente ao código de produção.

A pirâmide de testes é o modelo orientador: muitos testes baixos (unitários, rápidos, granulares), menos testes intermediários (integração, mais lentos), poucos testes de topo (end-to-end, lentos e frágeis). Inverter a pirâmide — depender principalmente de testes de topo — produz suites lentas, instáveis e que pouco ajudam o desenvolvimento. Manter o investimento na base produz feedback rápido e confiança alta.

## Camadas da Pirâmide

### Testes Unitários

Cobrem a lógica de domínio puro — agregados, value objects, domain services — sem inicializar contexto Spring, sem banco, sem rede. Tempo de execução é sub-segundo para a suite completa de um serviço, viabilizando execução contínua durante desenvolvimento (cada save dispara testes).

A regra é: testes unitários nunca falam com infraestrutura. Mocks são usados moderadamente — preferencialmente fakes (implementações in-memory simples) ou os próprios objetos do domínio. Excesso de mocks é sinal de design ruim: o código está acoplado a colaboradores específicos quando deveria depender de abstrações.

Mutation testing com PIT verifica a qualidade dos testes. PIT injeta mutações no código (troca > por <, remove um if, etc.) e verifica se algum teste falha. Mutações que sobrevivem revelam falhas na cobertura — o teste passa mas não realmente verifica a lógica. Targets de mutation score por módulo de domínio: 80%+.

### Testes de Arquitetura

ArchUnit aplica regras arquiteturais como testes executáveis. Exemplos das regras impostas no OrderFlow: a camada de domínio não pode importar de Spring nem de bibliotecas de infraestrutura; a camada de application só pode depender de domain (não de adapters específicos); classes anotadas com @RestController só podem residir na camada de adapter; nomes de classes seguem convenções (UseCases terminam em "UseCase", repositórios em "Repository", eventos em "Event"); pacotes seguem a estrutura hexagonal definida; ciclos entre pacotes são proibidos.

Estas regras rodam no CI a cada build. Quando alguém — bem-intencionado mas distraído — tenta violar a arquitetura, o build falha com mensagem clara. Isto previne a erosão silenciosa que tipicamente afunda projetos ao longo do tempo: cada violação isolada parece pequena; o acúmulo destrói a integridade.

### Testes de Integração

Validam que os componentes do serviço funcionam juntos com infraestrutura real. Não há mock de banco, mock de Kafka, mock de cache — Testcontainers sobe instâncias reais de PostgreSQL, MongoDB, Kafka, Redis em containers Docker durante a execução do teste.

A justificativa para evitar mocks de infraestrutura é prática: mocks divergem do comportamento real ao longo do tempo, especialmente em casos de borda (deadlocks de banco, falhas de rede no Kafka, timeouts). Testes que passam com mocks mas falham em produção são exatamente os testes que deveriam ter pego o problema.

Cada teste de integração obtém um banco limpo (database-per-test ou cleanup entre testes), evitando interdependência. Tempo de execução é da ordem de minutos para a suite, ainda viável no CI a cada commit.

### Testes de Contrato

Quando dois serviços se comunicam, mudanças no produtor podem quebrar consumidores silenciosamente. Testes de contrato detectam isso antes do deploy.

O OrderFlow usa Spring Cloud Contract para contratos HTTP e Pact para contratos baseados em mensagens. O produtor define o contrato (formato esperado de requisição/resposta ou evento), gera testes do lado do consumidor a partir do contrato, e o lado do produtor é verificado contra esses contratos no CI. Mudança quebrante no produtor falha o build antes de afetar consumidores em runtime.

Os contratos são versionados junto com o código e armazenados em um broker Pact compartilhado, permitindo que cada serviço saiba quais contratos precisa cumprir.

### Testes End-to-End

Cobrem caminhos críticos do sistema completo — saga de criação de pedido, fluxo de cancelamento, devolução. São executados contra um ambiente Kubernetes efêmero criado no CI para a pull request, com todos os serviços e infraestrutura reais.

E2E são poucos por design — cobrem 5-10 cenários de negócio críticos, não cada permutação. São intencionalmente caros e devem ser usados para validar que o sistema integrado funciona, não para validar lógica que pertence a camadas mais baixas.

Tempo de execução é de 10-15 minutos. Falhas em E2E são tratadas com seriedade — não retentativas cegas. Flakiness é investigada e corrigida; um teste que "às vezes falha" é pior que nenhum teste, pois treina a equipe a ignorar falhas.

### Testes de Carga e Performance

Gatling executa testes de carga noturnamente contra um ambiente staging dimensionado proporcionalmente a produção. Cenários cobrem padrões realistas de tráfego: rampa gradual até pico, sustentação do pico, decremento.

SLOs de performance são parte do build. Latência p99 acima de threshold falha o teste. Throughput abaixo de threshold falha. Esses números são tratados com a mesma seriedade que correctness: regressão de performance é regressão funcional.

Os resultados alimentam dashboards de tendência. Conforme o sistema evolui, podemos observar se mudanças melhoram, mantêm ou degradam performance ao longo do tempo.

### Testes de Caos

Injetar falhas intencionalmente em staging valida que a arquitetura se comporta sob falha como esperado. Chaos Mesh executa cenários como: matar pods aleatórios, introduzir latência de rede, particionar nodes do Kafka, sobrecarregar CPU/memória.

A expectativa é que o sistema degrade graciosamente, não catastroficamente. Circuit breakers abrem, retries respeitam limites, dead letter queues capturam mensagens problemáticas, alertas disparam para os SREs. Testes de caos validam que essas defesas estão configuradas e funcionando.

Inicialmente os testes rodam manualmente, em janelas controladas. Com maturidade, entram no ciclo automatizado executando regularmente em ambientes não-produtivos.

## Testes de Segurança

Segurança tem sua própria suite de validações. Análise estática (SonarQube com regras de segurança) detecta vulnerabilidades em código (uso indevido de criptografia, injection, deserialização insegura). Análise de dependências (Trivy, Dependabot) identifica CVEs em bibliotecas. Análise dinâmica (OWASP ZAP) testa endpoints rodando contra OWASP Top 10. Penetration tests externos são contratados periodicamente para complementar.

## Cobertura de Código

Cobertura é medida por JaCoCo, com gate no CI: 85% de cobertura de linhas e 80% de branches em módulos de domínio e application. Adaptadores não têm gate de cobertura — confiamos nos testes de integração para validá-los; medir cobertura de adaptadores frequentemente cria pressão para escrever testes artificiais que aumentam o número sem aumentar a confiança.

Cobertura é um indicador, não um objetivo. 85% com testes superficiais é pior que 70% com testes profundos. Mutation testing complementa cobertura — verifica que os testes realmente verificam comportamento, não apenas executam código.

## Testes como Documentação

Bons testes funcionam como documentação executável. Os nomes dos testes descrevem cenários ("orderShouldBeCancelledWhenPaymentFailsAndInventoryAlreadyReserved"); os corpos demonstram como usar a API; os asserts especificam o comportamento esperado.

Para esta documentação ser efetiva, testes precisam ser legíveis. Uso de builders fluentes para configurar cenários complexos, helpers compartilhados para boilerplate recorrente, e estrutura Given-When-Then explícita ajudam.

## Continuous Integration

A pipeline CI executa em ordem: lint, compile, testes unitários, testes de arquitetura, testes de integração, testes de contrato, análises de segurança, build de imagens. Cada estágio falha rápido — ordenamos do mais barato/rápido ao mais caro/lento, então um problema unitário não espera 20 minutos para ser detectado.

E2E e load tests rodam apenas em pull requests para `main` (não em todo commit), por custo. Chaos rodam apenas agendados em staging.

Tempo total de CI para PR para `main` deve estar abaixo de 30 minutos para preservar fluxo de trabalho. Acima disso, desenvolvedores começam a context-switch e produtividade despenca.

## Cultura

Por fim, a estratégia de testes só funciona se a cultura suportar. Princípios cultivados ativamente no projeto: bug em produção exige teste reproduzindo-o antes da correção; testes não são opcionais em PRs — são parte do escopo; flakes são corrigidos imediatamente, não tolerados; velocidade da suite é responsabilidade compartilhada — testes lentos são bug.

Estes princípios precisam ser modelados pela liderança técnica. Quando um líder pede "vamos pular os testes desta vez para chegar no prazo", autoriza implicitamente a todos fazerem o mesmo. A consistência cultural é o que sustenta a qualidade do código ao longo dos anos.
