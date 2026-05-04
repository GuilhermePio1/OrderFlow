# Event Sourcing e CQRS no OrderFlow

## O que é Event Sourcing

Event sourcing é um padrão de persistência onde, em vez de armazenar o estado atual de uma entidade, armazena-se a sequência completa de eventos que produziram esse estado. O estado atual é obtido replayando os eventos desde o início, ou a partir de snapshots periódicos.

Em sistemas tradicionais, atualizar um pedido significa um UPDATE numa tabela `orders`, sobrescrevendo o estado anterior. Em event sourcing, o mesmo cenário produz um INSERT numa tabela append-only de eventos: OrderPlaced, OrderItemAdded, OrderPaid, etc. O estado atual nunca é gravado diretamente — é sempre derivado.

## Por que Event Sourcing aqui

A decisão de aplicar event sourcing ao agregado Order é deliberada e justificada. Não é o padrão default — outros agregados no sistema (Payment, Inventory) usam persistência tradicional. Event sourcing tem custos reais (complexidade, curva de aprendizado, ferramental específico) e só compensa quando seus benefícios são necessários.

No contexto de Ordering, quatro razões justificam o investimento. Primeiro, requisito regulatório: pedidos têm implicações financeiras e fiscais; trilha de auditoria completa não é luxo, é obrigação. Cada mudança no pedido — quem fez, quando, o quê — precisa ser recuperável. Segundo, capacidade de consultas temporais: poder responder "qual era o estado do pedido em qualquer momento histórico" é crítico para suporte ao cliente, disputas e análise. Terceiro, ajuste natural com sagas: cada passo da saga é um evento, o que torna o estado da saga inerentemente persistido. Quarto, flexibilidade de modelos de leitura: requisitos de consulta mudam — com event sourcing, novas projeções podem ser construídas a partir do histórico completo a qualquer momento.

## Estrutura do Event Store

O event store é implementado em PostgreSQL. A tabela principal `events` é puramente append-only, com colunas que incluem `event_id` (UUID, chave primária), `aggregate_id` (ID do agregado ao qual o evento pertence), `aggregate_type` (tipo do agregado, "Order"), `event_type` (nome do evento, "OrderPlaced"), `event_version` (versão do schema), `sequence_number` (ordem do evento dentro do stream do agregado), `payload` (JSONB com os dados do evento), `metadata` (JSONB com contextual: traceId, userId, timestamp), e `occurred_at` (timestamp).

Índices são criados para suportar dois padrões de acesso principais: leitura do stream completo de um agregado (para rehidratação), e leitura por timestamp (para projeções e consultas temporais).

Concorrência otimista é aplicada no nível do agregado. Ao gravar novos eventos, o serviço verifica que o `expected_version` corresponde à versão atual do agregado no banco. Se outro processo já incrementou a versão, a gravação falha e a operação é retentada após rehidratar o estado mais recente.

## Snapshots

Para agregados de longa vida com muitos eventos, replayar todos os eventos a cada leitura torna-se caro. Snapshots periódicos resolvem isso: a cada N eventos (configurável, tipicamente 100), o estado atual do agregado é serializado e gravado como snapshot. A rehidratação carrega o snapshot mais recente e replaya apenas os eventos desde então.

Snapshots são uma otimização de leitura, não a fonte de verdade. Em caso de dúvida, podem ser regenerados a partir dos eventos. Seu schema pode ser alterado sem migração — basta invalidá-los.

## Padrão Outbox

Persistir um evento e publicá-lo em Kafka são duas operações em dois sistemas distintos. Sem cuidado, é fácil produzir cenários onde o evento foi gravado mas não publicado (ou vice-versa), gerando inconsistência observável.

O padrão Outbox elimina o problema. Numa única transação local no PostgreSQL, dois INSERTs acontecem: um na tabela `events` (o event store) e outro na tabela `outbox`. Ambos sucedem ou ambos falham — atomicidade trivial, garantida pelo banco.

Um processo separado lê a tabela outbox e publica os eventos no Kafka. No OrderFlow, este processo é o Debezium operando em modo CDC sobre o write-ahead log do PostgreSQL — abordagem com overhead mínimo no caminho crítico de escrita. Após publicação confirmada, os registros da outbox podem ser arquivados ou removidos.

A garantia resultante é "at least once" — eventos podem ser publicados mais de uma vez se o Debezium falhar entre publicar e marcar como concluído. Consumidores são desenhados para idempotência, deduplicando por `event_id`.

## CQRS: Comando e Consulta Separados

CQRS separa o modelo usado para escrita do modelo usado para leitura. No OrderFlow, esta separação é física, não apenas lógica.

O caminho de escrita do contexto Ordering recebe comandos via HTTP ou eventos Kafka, executa lógica de domínio no agregado event-sourced, e persiste novos eventos no PostgreSQL. Este caminho é otimizado para correção e expressividade do domínio, não para velocidade de leitura.

O caminho de leitura é servido pelo Query Service, que consome eventos do Kafka e mantém modelos denormalizados em MongoDB. Cada modelo é otimizado para um caso de uso de consulta específico — visão resumida do pedido, detalhes completos, histórico do cliente, listagens administrativas. Consultas neste caminho são puras leituras de documento, sem joins, sem agregações em tempo de query.

A vantagem é dupla. Primeiro, o modelo de domínio fica livre para expressar regras de negócio sem ser distorcido por requisitos de leitura — não é preciso achatar agregados para acelerar consultas. Segundo, escala horizontal independente: o caminho de leitura é tipicamente 90%+ do tráfego e pode ser escalado massivamente sem afetar o caminho transacional.

O custo é consistência eventual entre escrita e leitura. Após uma escrita, há um delay (tipicamente <100ms) até que a projeção reflita a mudança. A UI compensa isso através de updates otimistas, polling, ou WebSocket push quando a projeção atualiza.

## Construção de Projeções

Cada projeção é um consumer Kafka independente que processa eventos e atualiza seus documentos. Idempotência é mandatória — eventos podem ser entregues múltiplas vezes; processar duas vezes não pode produzir efeito diferente de processar uma vez. O `event_id` é usado como chave de idempotência: antes de aplicar um evento, verifica-se se ele já foi processado.

Ordenação é garantida via particionamento do tópico Kafka pelo aggregate_id. Eventos do mesmo pedido são processados em ordem por um único consumer; eventos de pedidos diferentes podem ser processados em paralelo.

Quando uma nova projeção precisa ser construída, ela começa do offset zero e processa todo o histórico — pode levar minutos ou horas dependendo do volume — até alcançar o presente, momento em que entra em modo "live" processando eventos novos conforme chegam.

## Versionamento de Eventos

Eventos são imutáveis e duradouros — vão existir por anos. Schema deles inevitavelmente precisa evoluir. Três estratégias são usadas conforme o tipo de mudança.

Mudanças aditivas (adicionar campo opcional) são compatíveis e não exigem ação especial — consumidores antigos ignoram o novo campo, novos consumidores leem eventos antigos com o campo ausente.

Mudanças semanticamente equivalentes (renomear campo, restruturar) são tratadas via upcasters: funções que transformam o JSON do evento antigo no formato novo no momento da leitura. O event store mantém o formato original imutável.

Mudanças quebrantes (semântica alterada) produzem um novo tipo de evento (OrderPlacedV2). Eventos antigos continuam existindo no histórico; código de aplicação interpreta ambos durante o período de transição. Eventualmente, um upcaster pode converter os antigos em novos.

## Replay e Rebuild

A capacidade de replayar eventos é uma das características definidoras do event sourcing. Cenários onde isso é valioso incluem: corrigir um bug que produziu projeções incorretas (regenerar a projeção limpa); criar uma nova projeção para um caso de uso recém-identificado; analisar comportamento histórico para detectar padrões; investigar incidentes de segurança rastreando todas as ações.

O Query Service expõe operações administrativas (autenticadas e auditadas) para iniciar rebuilds. Durante o rebuild, a versão antiga continua servindo até a nova alcançar o presente, então há um cutover atômico. Zero downtime para os clientes.

## Comparação com Persistência Tradicional

A pergunta legítima é: por que não usar persistência tradicional com uma tabela de auditoria? A resposta tem várias camadas.

Auditoria tradicional captura mudanças mas não a intenção. Sabe-se que o status mudou de PAID para CANCELLED, mas não fica claro se foi por estorno do pagamento, por reembolso, por fraude detectada, por solicitação do cliente. Eventos capturam a intenção: PaymentRefunded, FraudDetected, CustomerCancelled — semanticamente distintos.

Auditoria tradicional é tipicamente um esforço retrofitado, frequentemente com gaps (esqueceu-se de capturar uma operação) ou inconsistências (auditoria divergiu do estado real por bug). Eventos são a fonte de verdade, não algo paralelo — não há como divergir do que aconteceu porque os eventos SÃO o que aconteceu.

Reconstruir estado a partir de auditoria tradicional é tipicamente impossível na prática. Reconstruir a partir de eventos é a operação fundamental — testada milhares de vezes por dia em produção.

Existe um custo: complexidade conceitual maior, exigência de pensar em termos de eventos durante o design, ferramental específico, casos de borda (eventos fora de ordem, schemas evoluindo). Por isso o padrão é aplicado seletivamente, onde os benefícios pagam o custo.
