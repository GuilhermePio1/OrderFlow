# Domain-Driven Design no OrderFlow

## Por que DDD aqui?

Domain-Driven Design não é apropriado para todo projeto. Funciona bem quando o domínio possui complexidade essencial real — regras de negócio entrelaçadas, vocabulário especializado, mudanças frequentes guiadas pelo negócio. O domínio de gestão de pedidos para e-commerce satisfaz esses critérios: políticas de preço variam por região e canal; descontos têm regras combinatórias; estoque tem reservas, alocações, kits; pagamentos têm autorização, captura, estorno parcial; envios têm rotas, transportadoras, prazos. Tentar modelar isso como CRUD anêmico produz código procedural disfarçado e inevitável dívida.

Onde DDD seria exagero (um simples cadastro de usuários, um proxy de API), o projeto prefere ser pragmático. DDD é aplicado onde paga seu custo — predominantemente no contexto de Ordering, parcialmente em Payment e Inventory, e propositalmente ausente em contextos puramente técnicos como o Query Service.

## Linguagem Ubíqua

A linguagem ubíqua é construída em conjunto com especialistas de domínio (na vida real) ou pesquisada em referências canônicas (no caso deste projeto). Termos têm significado preciso e único em cada contexto. "Order" no contexto de Ordering é o agregado completo com itens, totais e estado; em Notification, é apenas uma referência para construir uma mensagem ao cliente. "Reservation" em Inventory é uma alocação temporária de estoque; em Hotel Booking (se existisse) seria uma reserva de quarto — palavras iguais, conceitos diferentes, contextos diferentes.

Glossários por contexto são mantidos em `docs/glossary/` e atualizados conforme a linguagem evolui. Mudanças no glossário propagam para nomes de classes, métodos, eventos, e mensagens de log — código e linguagem caminham juntos.

## Mapa de Contextos

Cinco contextos delimitados compõem o sistema, com relações explícitas entre si.

O contexto de **Ordering** é o core domain. É onde a vantagem competitiva reside (regras de preço, fluxo de pedido) e onde mais investimento em modelagem se justifica. Implementa Event Sourcing, agregados ricos, e CQRS. Atua como upstream em relação a Payment e Inventory, publicando eventos que estes consomem.

O contexto de **Payment** é supporting domain. Importante mas não distintivo — pagamentos são uma commodity. Implementa modelo transacional clássico, com forte consistência, e atua como Customer/Supplier downstream de Ordering. Possui uma Anti-Corruption Layer (ACL) ao se comunicar com gateways de pagamento externos (Stripe, PagSeguro), traduzindo modelos externos para o vocabulário interno.

O contexto de **Inventory** é supporting domain. Mantém disciplina de modelo próprio mas o investimento em DDD é proporcional — agregados claros mas não event-sourced.

O contexto de **Notification** é generic subdomain. Funcionalidade indistinguível da concorrência. Modelado de forma simples, com foco em confiabilidade e escala, não em riqueza de domínio. Consome eventos de todos os outros contextos através de uma ACL que abstrai diferenças entre os formatos de eventos.

O contexto de **Query** é tecnicamente um contexto, mas seu papel é integração — consolida visões de leitura. Implementa o padrão Open Host Service, expondo uma API uniforme de consulta para clientes externos.

## Padrões de Integração entre Contextos

A relação entre Ordering e os contextos a jusante é Customer/Supplier. Ordering, como upstream, publica eventos que formam um contrato. Mudanças incompatíveis nestes eventos exigem versionamento (v1, v2 do tópico) e período de coexistência. Os contextos a jusante (Payment, Inventory) têm voz nas decisões de schema dos eventos — não podem ser surpreendidos.

A relação com sistemas externos (gateways de pagamento, transportadoras) é Anti-Corruption Layer. Modelos externos não vazam para dentro do domínio. Adapters traduzem entre o vocabulário externo e o interno, isolando o domínio de mudanças nas APIs externas.

A relação do Query Service com os outros contextos é Open Host Service consumindo eventos publicados. Define seu próprio modelo de leitura adequado às necessidades dos clientes, sem que os contextos upstream precisem se preocupar com os detalhes.

## Agregados

Um agregado é um cluster de objetos tratados como uma unidade de consistência. Modificações ao agregado passam pelo aggregate root, garantindo que invariantes sejam mantidas. No contexto de Ordering, o agregado raiz Order contém OrderItems como entidades filhas e referencia Customer e Product apenas por identidade — nunca os contém.

A regra de design é: dentro de um agregado, consistência é forte e transacional; entre agregados, consistência é eventual. Isso mantém os agregados pequenos e desbloqueia escalabilidade — não há transações distribuídas grandes.

O agregado Order tem como invariantes: o total deve sempre igualar a soma dos subtotais dos itens; transições de estado seguem uma máquina de estados explícita (PLACED → PAID → INVENTORY_RESERVED → SHIPPED → DELIVERED, com ramos para CANCELLED em vários pontos); apenas certos comandos são aceitos em cada estado. Toda violação dessas invariantes produz uma exceção de domínio antes que qualquer evento seja gravado.

No contexto de Payment, o agregado Payment encapsula uma autorização e suas capturas e estornos. No contexto de Inventory, Product é o agregado raiz com StockLevel e Reservations como entidades filhas.

## Value Objects

Value objects são imutáveis, identificados por seus atributos (não por identidade), e modelam conceitos do domínio que não têm ciclo de vida próprio. O projeto usa value objects extensivamente: Money (valor + moeda, com operações aritméticas que respeitam moeda), OrderId, CustomerId, ProductId (tipados, evitando confusão entre IDs), Quantity (quantidade positiva), Address (endereço com validação estrutural), TrackingNumber.

O ganho de tipagem forte é substancial. Um método que recebe `Money price, Money discount` é impossível de confundir; um que recebe `BigDecimal, BigDecimal` é uma armadilha esperando para ativar.

## Domain Events

Domain events expressam algo significativo que aconteceu no domínio. São fatos imutáveis no passado: OrderPlaced, PaymentAuthorized, InventoryReserved. Não são comandos ("pague o pedido") — são notificações ("o pedido foi pago").

Cada evento carrega o que é necessário para os consumidores reagirem, sem expor detalhes internos do agregado que o emitiu. Por exemplo, OrderPlaced inclui orderId, customerId, items, totalAmount, e timestamp — informação suficiente para Payment iniciar autorização e para Notification preparar a confirmação. Não inclui detalhes irrelevantes como o IP de origem ou o user-agent.

Eventos são versionados em seu schema. Mudanças aditivas (novos campos opcionais) são compatíveis; mudanças quebrantes exigem novo evento (OrderPlacedV2) com período de transição.

## Domain Services

Quando uma operação não pertence naturalmente a um agregado específico, um domain service expressa-a. Por exemplo, calcular o frete de um pedido envolve o pedido e regras de envio que dependem de múltiplos agregados — vai para um ShippingCalculator domain service, não dentro de Order.

Domain services são objetos sem estado, expressando lógica pura de domínio. Não são confundidos com application services (que orquestram casos de uso) nem com infrastructure services (que falam com o mundo externo).

## Repositories

Cada agregado tem um repositório que abstrai persistência. A interface vive na camada de domínio (`OrderRepository`); a implementação vive na camada de infraestrutura. Esta inversão preserva o domínio puro de detalhes de persistência.

Para o agregado Order, o repositório opera em termos de eventos — `save(Order)` persiste os eventos não-comprometidos; `findById(OrderId)` rehidrata o agregado a partir do stream de eventos. A complexidade do event sourcing fica encapsulada no repositório.

## Hexagonal Architecture

Cada serviço é estruturado segundo arquitetura hexagonal (também conhecida como ports and adapters). No centro vive a lógica de domínio pura, sem dependências de framework. Em volta, ports definem as operações que o domínio precisa do mundo externo (interfaces). Na camada externa, adapters implementam essas ports usando tecnologias específicas (Spring, R2DBC, Kafka).

Esta estrutura tem dois benefícios concretos. Primeiro, permite testar o domínio sem inicializar nenhum framework — testes unitários são instantâneos. Segundo, mudar a tecnologia de uma adapter (de Kafka para SQS, por exemplo) é localizada — o domínio não muda.

ArchUnit aplica regras de arquitetura como testes: a camada de domínio não pode importar de Spring; a camada de application não pode importar de adapters específicos; nomes de classes seguem convenções (UseCases terminam em "UseCase", repositórios em "Repository"). Estas regras são executadas no CI a cada build, prevenindo erosão arquitetural ao longo do tempo.
