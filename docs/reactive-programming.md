# Programação Reativa no OrderFlow

## O que é Programação Reativa

Programação reativa é um paradigma assíncrono baseado em streams de dados e propagação de mudanças. Em vez de pensar em valores ("o resultado da query"), pensa-se em fluxos de valores ao longo do tempo ("a sequência de resultados conforme chegam"). Em vez de bloquear uma thread esperando por uma operação de I/O, registra-se uma callback (ou seu equivalente em operadores de stream) e a thread fica livre para outro trabalho.

A especificação Reactive Streams define quatro contratos centrais: Publisher (produz valores), Subscriber (consome valores), Subscription (controla o fluxo), e Processor (atua como ambos). Project Reactor, a biblioteca usada no OrderFlow, implementa esta especificação com dois tipos principais: Mono (zero ou um valor) e Flux (zero a N valores).

## Por que Programação Reativa aqui

A decisão de usar programação reativa não foi automática. Reactive não é "melhor" que imperativo — é melhor para certos problemas e pior para outros. A pergunta certa é onde se aplica e onde não.

Reactive brilha em sistemas com alta concorrência de I/O e baixo CPU por requisição — exatamente o perfil de muitos serviços em microsserviços modernos, que passam a maior parte do tempo esperando por bancos, caches, outros serviços. Uma thread bloqueada esperando por I/O é uma thread desperdiçada. Reactive permite que uma única thread sirva milhares de requisições intercalando os trechos de CPU enquanto I/O está em andamento.

Reactive sofre quando o código é predominantemente CPU-bound (não há ganho — apenas overhead) ou quando a lógica é fortemente transacional com semântica de banco bloqueante (muito boilerplate para integrar). Por isso, o OrderFlow aplica reactive seletivamente.

## Onde Reactive é Aplicado

O Order Service e o Query Service são totalmente não-bloqueantes, construídos sobre Spring WebFlux com Project Reactor e R2DBC. A justificativa é diferente para cada um.

O Order Service recebe alta concorrência de criação de pedidos em horários de pico. Cada pedido envolve várias operações de I/O encadeadas: validar cliente (cache Redis ou DB), validar produtos (cache ou DB), gravar evento no event store, gravar na outbox. Reactive permite processar essas etapas de forma assíncrona, com uma única thread servindo muitas requisições simultaneamente. Em testes de carga, uma instância reativa atinge throughput de 3-4x uma instância equivalente bloqueante com o mesmo footprint de memória.

O Query Service serve a grande maioria do tráfego de leitura — listagens, detalhes, histórico. Cada consulta é uma leitura de MongoDB, possivelmente com cache Redis na frente. Backpressure é especialmente valiosa aqui: se o MongoDB ficar lento, a pressão se propaga até o cliente em vez de acumular requisições e estourar memória.

## Onde Reactive Não É Aplicado

O Payment Service e o Inventory Service usam Spring MVC tradicional bloqueante, mas com Java 21 virtual threads habilitadas. Esta combinação oferece a maior parte dos benefícios de concorrência do reactive sem a complexidade conceitual.

A razão é que esses serviços têm lógica fortemente transacional. O Payment Service grava transações em PostgreSQL com isolamento serializable em alguns casos, comunica com gateways externos com timeouts agressivos, lida com idempotência baseada em chaves de transação. Misturar essa lógica com pipelines reativos adiciona complexidade sem ganho proporcional. Virtual threads, introduzidas no Java 21, permitem código bloqueante simples mantendo concorrência alta — uma JVM com virtual threads pode ter dezenas de milhares de "threads" sem custo proibitivo, pois são gerenciadas pela JVM em poucos sistemas operacionais.

Esta heterogeneidade é deliberada. Microsserviços permitem escolhas locais — cada serviço usa o paradigma adequado ao seu problema. Forçar uniformidade em nome de "consistência" seria sacrificar adequação técnica por estética arbitrária.

## Princípios do Código Reativo

Vários princípios guiam a implementação reativa para que os benefícios se materializem na prática.

Toda operação de I/O deve ser reativa. Misturar uma chamada bloqueante (JDBC, biblioteca síncrona qualquer) num pipeline reativo bloqueia a thread do event loop, anulando os benefícios. Bibliotecas inevitavelmente bloqueantes (raras, mas existem) são isoladas em schedulers dedicados via `subscribeOn(Schedulers.boundedElastic())`.

Backpressure deve ser respeitada de ponta a ponta. Conectores reativos para banco (R2DBC) e Kafka (Reactor Kafka) propagam backpressure naturalmente. Se um consumidor está lento, a pressão se propaga até o produtor, evitando overflow. Operadores que quebram backpressure (`buffer()`, `cache()` com tamanho ilimitado) são usados com cuidado.

Streams devem ser imutáveis e composíveis. Cada operador retorna um novo stream; o original é preservado. Isso facilita teste, reutilização e raciocínio sobre o pipeline.

Nada acontece até que haja subscrição. Pipelines reativos são lazy — descrevem uma computação mas não a executam. A subscrição é feita implicitamente pelo framework (Spring chama `subscribe()` para você). Esquecer-se disso e descrever um pipeline sem subscrevê-lo é um bug clássico de iniciante reativo.

## Tratamento de Erros

Tratamento de erros em código reativo é diferente de imperativo, e exige atenção. Erros se propagam pelo stream como sinais especiais, junto com `onNext` e `onComplete`. Operadores como `onErrorResume`, `onErrorReturn`, `retry` permitem tratamento.

A regra geral é: trate erros o mais perto possível da causa, com a estratégia adequada — recuperação (`onErrorResume` retornando valor default), retry (com backoff e limite), ou propagação para o caller (sem catch). Engolir erros silenciosamente é o pior antipattern.

Cada operação externa tem timeout explícito via `timeout(Duration)`. Sem timeout, um stream pode pendurar indefinidamente esperando por I/O.

## Testabilidade

Reactive é altamente testável quando tratado corretamente. O `StepVerifier` da Reactor Test permite verificar comportamento step-by-step de qualquer pipeline: "espero 3 elementos com estes valores, depois um erro do tipo X".

Testes virtualizam o tempo via `VirtualTimeScheduler`, permitindo testar operações time-based (debounce, delay, retry com backoff) sem realmente esperar. Um teste que lógica deveria reagir após 30 segundos roda em milissegundos.

## Observabilidade Reativa

Pipelines reativos são opacos por padrão — uma stack trace de erro é tipicamente dominada por frames internos do Reactor, não pelo código de aplicação. Para mitigar, o OrderFlow habilita o `Hooks.onOperatorDebug()` em desenvolvimento (custo alto, não em produção) e usa o operador `checkpoint()` em pontos estratégicos do pipeline para gerar traces mais úteis.

Métricas customizadas registram latência por operador chave, profundidade de buffers, e sinais de backpressure. OpenTelemetry instrumenta automaticamente a maior parte do tráfego reativo (HTTP, R2DBC, Kafka), produzindo traces distribuídos ricos.

## Curva de Aprendizado

Reactive tem curva de aprendizado real. A equipe precisa entender semântica de operadores, scheduling, backpressure, debugging de pipelines. Erros comuns incluem: bloquear inadvertidamente (sem perceber que uma chamada é bloqueante), criar sub-streams hot quando deveriam ser cold, esquecer de tratar erros e produzir streams que silenciosamente nunca completam.

O projeto mitiga isso através de práticas: workshops internos, code reviews focados nos antipadrões conhecidos, biblioteca interna de helpers para padrões recorrentes (compor um Mono com retry+timeout+métricas), e ArchUnit detectando uso de bibliotecas bloqueantes nos serviços reativos.

## Quando Não Usar Reactive

Vale registrar explicitamente os cenários onde reactive seria a escolha errada, mesmo num sistema que o adota: rotinas batch ou agendadas (CPU-bound, paralelismo simples basta); ferramentas de linha de comando (sem concorrência, a complexidade não compensa); aplicações com baixa concorrência geral (algumas requisições por segundo — qualquer paradigma serve, prefira o mais simples); equipes sem experiência prévia em paradigma assíncrono e sem disposição para aprender (a complexidade vai virar bugs).

A escolha consciente entre reactive e bloqueante (ou virtual threads) por serviço é, ela mesma, uma demonstração de maturidade arquitetural. Sistemas que aplicam reactive uniformemente "porque é moderno" frequentemente pagam custos sem colher benefícios proporcionais.
